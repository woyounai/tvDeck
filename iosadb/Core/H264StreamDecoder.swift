import CoreMedia
import Foundation
import VideoToolbox

/// A reference wrapper makes Core Media samples safe to hand across the
/// parser queue without copying their compressed payload.
final class H264VideoSample: @unchecked Sendable {
    let sampleBuffer: CMSampleBuffer
    let dimensions: CGSize
    let isSync: Bool

    init(
        sampleBuffer: CMSampleBuffer,
        dimensions: CGSize,
        isSync: Bool
    ) {
        self.sampleBuffer = sampleBuffer
        self.dimensions = dimensions
        self.isSync = isSync
    }
}

/// Incrementally converts Android screenrecord's Annex-B H.264 stream into
/// AVCC samples and decodes on a dedicated serial queue with VideoToolbox.
/// Only independent IOSurface-backed images reach the latest-frame display.
final class H264StreamDecoder: @unchecked Sendable {
    private let parserQueue = DispatchQueue(label: "com.tvadb.live-preview.decode", qos: .userInteractive)
    private var invalidated = false
    private var decompressionSession: VTDecompressionSession?
    private var annexBBuffer = Data()
    private var sequenceParameterSet: Data?
    private var pictureParameterSet: Data?
    private var formatDescription: CMVideoFormatDescription?
    private var currentPictureNALUnits: [Data] = []
    private var currentPictureIsIDR = false
    private var presentationFrameIndex: Int64 = 0

    private let stateLock = NSLock()
    private var emittedFrameCount = 0
    private var decodingFailure: OSStatus?
    private let onSample: @Sendable (H264VideoSample) -> Void

    init(onSample: @escaping @Sendable (H264VideoSample) -> Void) {
        self.onSample = onSample
    }

    var hasDecodedFrame: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return emittedFrameCount > 0
    }

    var failureStatus: OSStatus? {
        stateLock.lock(); defer { stateLock.unlock() }
        return decodingFailure
    }

    var hasVideoParameterSets: Bool {
        parserQueue.sync { sequenceParameterSet != nil && pictureParameterSet != nil }
    }

    private func recordFailure(_ status: OSStatus) {
        guard status != noErr else { return }
        stateLock.lock(); decodingFailure = status; stateLock.unlock()
    }

    func append(_ data: Data) async {
        guard !data.isEmpty else { return }
        await withCheckedContinuation { continuation in
            parserQueue.async { [self] in
                if !invalidated {
                    annexBBuffer.append(data)
                    extractAvailableNALUnits()
                    emitPictureAtPendingBoundary()
                }
                continuation.resume()
            }
        }
    }

    /// The helper supplies a complete codec access unit, including its final NAL.
    func appendAccessUnit(_ data: Data) async {
        await withCheckedContinuation { continuation in
            parserQueue.async { [self] in
                if !invalidated {
                    annexBBuffer.append(data)
                    extractAvailableNALUnits()
                    if let start = startCode(in: annexBBuffer, from: 0) {
                        processNALUnit(annexBBuffer.subdata(in: start.upperBound..<annexBBuffer.count))
                    }
                    annexBBuffer.removeAll(keepingCapacity: true)
                    emitCurrentPictureIfNeeded()
                }
                continuation.resume()
            }
        }
    }

    func invalidate() {
        parserQueue.sync {
            invalidated = true
            if let session = decompressionSession { VTDecompressionSessionInvalidate(session) }
            decompressionSession = nil
            formatDescription = nil
            sequenceParameterSet = nil
            pictureParameterSet = nil
            currentPictureNALUnits.removeAll()
            annexBBuffer.removeAll()
        }
    }

    private func extractAvailableNALUnits() {
        while true {
            guard let firstStartCode = startCode(
                in: annexBBuffer,
                from: 0
            ) else {
                if annexBBuffer.count > 1_048_576 {
                    annexBBuffer.removeAll(keepingCapacity: true)
                }
                return
            }

            if firstStartCode.lowerBound > 0 {
                annexBBuffer.removeSubrange(
                    0..<firstStartCode.lowerBound
                )
                continue
            }

            guard let nextStartCode = startCode(
                in: annexBBuffer,
                from: firstStartCode.upperBound
            ) else {
                return
            }

            let nalUnit = annexBBuffer.subdata(
                in: firstStartCode.upperBound..<nextStartCode.lowerBound
            )
            annexBBuffer.removeSubrange(0..<nextStartCode.lowerBound)
            processNALUnit(nalUnit)
        }
    }

    private func emitPictureAtPendingBoundary() {
        guard let start = startCode(in: annexBBuffer, from: 0), start.lowerBound == 0 else { return }
        let headerIndex = start.upperBound
        guard headerIndex < annexBBuffer.count else { return }
        let payloadIndex = headerIndex + 1
        if Self.startsNewPicture(header: annexBBuffer[headerIndex],
                                 firstPayloadByte: payloadIndex < annexBBuffer.count ? annexBBuffer[payloadIndex] : nil) {
            // Only the preceding complete picture is emitted; the partial NAL stays buffered.
            emitCurrentPictureIfNeeded()
        }
    }

    static func startsNewPicture(header: UInt8, firstPayloadByte: UInt8?) -> Bool {
        switch header & 0x1F {
        case 7, 8, 9: return true
        case 1, 5: return firstPayloadByte.map { $0 & 0x80 != 0 } ?? false
        default: return false
        }
    }

    private func startCode(
        in data: Data,
        from startOffset: Int
    ) -> Range<Int>? {
        guard data.count >= 3,
              startOffset <= data.count - 3 else {
            return nil
        }
        return data.withUnsafeBytes { rawBuffer in
            let bytes = rawBuffer.bindMemory(to: UInt8.self)
            var index = max(startOffset, 0)
            while index <= bytes.count - 3 {
                guard bytes[index] == 0,
                      bytes[index + 1] == 0 else {
                    index += 1
                    continue
                }
                if bytes[index + 2] == 1 {
                    return index..<(index + 3)
                }
                if index <= bytes.count - 4,
                   bytes[index + 2] == 0,
                   bytes[index + 3] == 1 {
                    return index..<(index + 4)
                }
                index += 1
            }
            return nil
        }
    }

    private func processNALUnit(_ nalUnit: Data) {
        guard let header = nalUnit.first else { return }
        let type = header & 0x1F
        switch type {
        case 7:
            emitCurrentPictureIfNeeded()
            if sequenceParameterSet != nalUnit {
                sequenceParameterSet = nalUnit
                rebuildFormatDescriptionIfPossible()
            }
        case 8:
            emitCurrentPictureIfNeeded()
            if pictureParameterSet != nalUnit {
                pictureParameterSet = nalUnit
                rebuildFormatDescriptionIfPossible()
            }
        case 1, 5:
            let startsNewPicture =
                firstMacroblockIndex(in: nalUnit) == 0
            if !currentPictureNALUnits.isEmpty,
               startsNewPicture {
                emitCurrentPictureIfNeeded()
            }
            currentPictureNALUnits.append(nalUnit)
            currentPictureIsIDR = currentPictureIsIDR || type == 5
        case 9:
            // Access Unit Delimiters explicitly terminate the previous frame.
            emitCurrentPictureIfNeeded()
        default:
            break
        }
    }

    private func rebuildFormatDescriptionIfPossible() {
        guard let sequenceParameterSet,
              let pictureParameterSet else {
            return
        }

        var newFormatDescription: CMFormatDescription?
        let status = sequenceParameterSet.withUnsafeBytes {
            sequenceBytes in
            pictureParameterSet.withUnsafeBytes { pictureBytes in
                guard let sequenceAddress = sequenceBytes
                    .bindMemory(to: UInt8.self).baseAddress,
                    let pictureAddress = pictureBytes
                    .bindMemory(to: UInt8.self).baseAddress else {
                    return kCMFormatDescriptionError_InvalidParameter
                }
                let parameterSetPointers = [
                    sequenceAddress,
                    pictureAddress,
                ]
                let parameterSetSizes = [
                    sequenceParameterSet.count,
                    pictureParameterSet.count,
                ]
                return parameterSetPointers.withUnsafeBufferPointer {
                    pointerBuffer in
                    parameterSetSizes.withUnsafeBufferPointer {
                        sizeBuffer in
                        CMVideoFormatDescriptionCreateFromH264ParameterSets(
                            allocator: kCFAllocatorDefault,
                            parameterSetCount: 2,
                            parameterSetPointers:
                                pointerBuffer.baseAddress!,
                            parameterSetSizes: sizeBuffer.baseAddress!,
                            nalUnitHeaderLength: 4,
                            formatDescriptionOut:
                                &newFormatDescription
                        )
                    }
                }
            }
        }
        guard status == noErr,
              let newFormatDescription else {
            recordFailure(status)
            return
        }
        formatDescription = newFormatDescription
        if let session = decompressionSession { VTDecompressionSessionInvalidate(session) }
        decompressionSession = nil
        let attributes: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as [String: Any]
        ]
        let specification: [CFString: Any] = [
            kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: true
        ]
        var session: VTDecompressionSession?
        let creationStatus = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault, formatDescription: newFormatDescription,
            decoderSpecification: specification as CFDictionary,
            imageBufferAttributes: attributes as CFDictionary,
            outputCallback: nil, decompressionSessionOut: &session
        )
        recordFailure(creationStatus)
        if creationStatus == noErr, let session {
            VTSessionSetProperty(session, key: kVTDecompressionPropertyKey_RealTime, value: kCFBooleanTrue)
            decompressionSession = session
        }
    }

    private func emitCurrentPictureIfNeeded() {
        guard !currentPictureNALUnits.isEmpty else { return }
        let nalUnits = currentPictureNALUnits
        let isIDR = currentPictureIsIDR
        currentPictureNALUnits.removeAll(keepingCapacity: true)
        currentPictureIsIDR = false
        emitPictureNALUnits(nalUnits, isIDR: isIDR)
    }

    private func emitPictureNALUnits(
        _ nalUnits: [Data],
        isIDR: Bool
    ) {
        guard let formatDescription,
              !nalUnits.isEmpty else {
            return
        }

        var sampleData = Data()
        sampleData.reserveCapacity(
            nalUnits.reduce(0) {
                $0 + MemoryLayout<UInt32>.size + $1.count
            }
        )
        for nalUnit in nalUnits {
            var length = UInt32(nalUnit.count).bigEndian
            sampleData.append(
                Data(
                    bytes: &length,
                    count: MemoryLayout<UInt32>.size
                )
            )
            sampleData.append(nalUnit)
        }

        var blockBuffer: CMBlockBuffer?
        let blockStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: sampleData.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: sampleData.count,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard blockStatus == kCMBlockBufferNoErr,
              let blockBuffer else {
            return
        }
        let copyStatus = sampleData.withUnsafeBytes { bytes in
            guard let source = bytes.baseAddress else {
                return kCMBlockBufferBadPointerParameterErr
            }
            return CMBlockBufferReplaceDataBytes(
                with: source,
                blockBuffer: blockBuffer,
                offsetIntoDestination: 0,
                dataLength: sampleData.count
            )
        }
        guard copyStatus == kCMBlockBufferNoErr else { return }

        var timing = CMSampleTimingInfo(
            duration: CMTime(value: 1, timescale: 60),
            presentationTimeStamp: CMTime(
                value: presentationFrameIndex,
                timescale: 60
            ),
            decodeTimeStamp: .invalid
        )
        var sampleBuffer: CMSampleBuffer?
        var sampleSize = sampleData.count
        let sampleStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: formatDescription,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )
        guard sampleStatus == noErr, let sampleBuffer else { return }
        presentationFrameIndex += 1

        // These are per-sample decoder attachments, not general
        // CMAttachmentBearer metadata. AVSampleBufferDisplayLayer ignores a
        // display-immediately flag attached to the outer sample buffer.
        if let attachmentArray =
            CMSampleBufferGetSampleAttachmentsArray(
                sampleBuffer,
                createIfNecessary: true
            ),
           CFArrayGetCount(attachmentArray) > 0,
           let rawAttachment = CFArrayGetValueAtIndex(
                attachmentArray,
                0
           ) {
            let attachment = Unmanaged<CFMutableDictionary>
                .fromOpaque(rawAttachment)
                .takeUnretainedValue()
            CFDictionarySetValue(
                attachment,
                Unmanaged.passUnretained(
                    kCMSampleAttachmentKey_DisplayImmediately
                ).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
            if !isIDR {
                CFDictionarySetValue(
                    attachment,
                    Unmanaged.passUnretained(
                        kCMSampleAttachmentKey_NotSync
                    ).toOpaque(),
                    Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
                )
            }
        }

        guard let session = decompressionSession else { return }
        // Synchronous decoding on the background queue prevents an unbounded
        // asynchronous decode backlog. Network reads await this bounded work.
        let decodeStatus = VTDecompressionSessionDecodeFrame(
            session, sampleBuffer: sampleBuffer, flags: [], infoFlagsOut: nil
        ) { [weak self] status, _, image, timestamp, _ in
            guard status == noErr, let image else { self?.recordFailure(status); return }
            self?.emitDecodedImage(image, timestamp: timestamp)
        }
        recordFailure(decodeStatus)
    }

    private func emitDecodedImage(_ image: CVImageBuffer, timestamp: CMTime) {
        var description: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault, imageBuffer: image,
            formatDescriptionOut: &description
        ) == noErr, let description else { return }
        var timing = CMSampleTimingInfo(duration: .invalid,
            presentationTimeStamp: timestamp, decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault, imageBuffer: image,
            formatDescription: description, sampleTiming: &timing,
            sampleBufferOut: &sample
        ) == noErr, let sample else { return }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
           let raw = CFArrayGetValueAtIndex(attachments, 0) {
            let dictionary = Unmanaged<CFMutableDictionary>.fromOpaque(raw).takeUnretainedValue()
            CFDictionarySetValue(dictionary,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        stateLock.lock(); emittedFrameCount += 1; stateLock.unlock()
        onSample(H264VideoSample(sampleBuffer: sample,
            dimensions: CGSize(width: CVPixelBufferGetWidth(image), height: CVPixelBufferGetHeight(image)),
            isSync: true))
    }

    /// H.264's first slice field is `first_mb_in_slice` (unsigned
    /// Exp-Golomb). A zero value begins a new picture; a non-zero value is an
    /// additional slice belonging to the current picture.
    private func firstMacroblockIndex(
        in nalUnit: Data
    ) -> UInt? {
        guard nalUnit.count > 1 else { return nil }
        var rbsp: [UInt8] = []
        rbsp.reserveCapacity(nalUnit.count - 1)
        var consecutiveZeros = 0
        for byte in nalUnit.dropFirst() {
            if consecutiveZeros >= 2, byte == 0x03 {
                consecutiveZeros = 0
                continue
            }
            rbsp.append(byte)
            if byte == 0 {
                consecutiveZeros += 1
            } else {
                consecutiveZeros = 0
            }
        }

        var bitOffset = 0
        var leadingZeros = 0
        while let bit = bit(at: bitOffset, in: rbsp), bit == 0 {
            leadingZeros += 1
            bitOffset += 1
            guard leadingZeros < 32 else { return nil }
        }
        guard bit(at: bitOffset, in: rbsp) == 1 else { return nil }
        bitOffset += 1

        var suffix: UInt = 0
        for _ in 0..<leadingZeros {
            guard let value = bit(at: bitOffset, in: rbsp) else {
                return nil
            }
            suffix = (suffix << 1) | UInt(value)
            bitOffset += 1
        }
        return (UInt(1) << UInt(leadingZeros)) - 1 + suffix
    }

    private func bit(
        at offset: Int,
        in bytes: [UInt8]
    ) -> UInt8? {
        let byteIndex = offset / 8
        guard byteIndex < bytes.count else { return nil }
        let shift = 7 - (offset % 8)
        return (bytes[byteIndex] >> UInt8(shift)) & 1
    }
}

final class PreviewFrameMetadata: @unchecked Sendable {
    private let lock = NSLock()
    private var size: CGSize?
    func changed(to newSize: CGSize) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard size != newSize else { return false }
        size = newSize; return true
    }
}
