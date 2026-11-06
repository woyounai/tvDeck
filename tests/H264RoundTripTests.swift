import Foundation
import CoreMedia
import CoreVideo
import VideoToolbox

final class EncodedFrames: @unchecked Sendable {
    private let lock = NSLock()
    private var bytes = Data()
    private var units: [Data] = []
    func append(_ sample: CMSampleBuffer) {
        guard let description = CMSampleBufferGetFormatDescription(sample),
              let block = CMSampleBufferGetDataBuffer(sample) else { return }
        var output = Data()
        for index in 0..<2 {
            var pointer: UnsafePointer<UInt8>?
            var size = 0
            guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(description,
                parameterSetIndex: index, parameterSetPointerOut: &pointer,
                parameterSetSizeOut: &size, parameterSetCountOut: nil,
                nalUnitHeaderLengthOut: nil) == noErr, let pointer else { return }
            output.append(contentsOf: [0, 0, 0, 1]); output.append(pointer, count: size)
        }
        var avcc = [UInt8](repeating: 0, count: CMBlockBufferGetDataLength(block))
        guard CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: avcc.count,
            destination: &avcc) == noErr else { return }
        var index = 0
        while index + 4 <= avcc.count {
            let count = Int(avcc[index]) << 24 | Int(avcc[index+1]) << 16
                | Int(avcc[index+2]) << 8 | Int(avcc[index+3])
            index += 4
            guard count > 0 && index + count <= avcc.count else { return }
            output.append(contentsOf: [0, 0, 0, 1]); output.append(contentsOf: avcc[index..<index+count])
            index += count
        }
        lock.lock(); units.append(output); lock.unlock()
        output.append(contentsOf: [0, 0, 0, 1, 9, 0xf0])
        lock.lock(); bytes.append(output); lock.unlock()
    }
    var data: Data { lock.lock(); defer { lock.unlock() }; return bytes }
    var accessUnits: [Data] { lock.lock(); defer { lock.unlock() }; return units }
}
final class DecodedFrames: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func receive(_ sample: H264VideoSample) {
        assert(sample.dimensions == CGSize(width: 160, height: 96))
        assert(CMSampleBufferGetImageBuffer(sample.sampleBuffer) != nil)
        lock.lock(); count += 1; lock.unlock()
    }
    var total: Int { lock.lock(); defer { lock.unlock() }; return count }
}
@main struct H264RoundTripTests {
    static func main() async throws {
        assert(!H264StreamDecoder.startsNewPicture(header: 0x41, firstPayloadByte: nil))
        assert(!H264StreamDecoder.startsNewPicture(header: 0x41, firstPayloadByte: 0x40))
        assert(!H264StreamDecoder.startsNewPicture(header: 0x06, firstPayloadByte: 0x80))
        assert(H264StreamDecoder.startsNewPicture(header: 0x41, firstPayloadByte: 0x80))
        assert(H264StreamDecoder.startsNewPicture(header: 0x65, firstPayloadByte: 0xff))
        assert(H264StreamDecoder.startsNewPicture(header: 9, firstPayloadByte: nil))
        let unavailable = H264StreamDecoder { _ in assertionFailure("Non-video data produced a frame") }
        await unavailable.append(Data("screenrecord: unsupported output format".utf8))
        assert(!unavailable.hasDecodedFrame && !unavailable.hasVideoParameterSets)
        assert(unavailable.failureStatus == nil)
        unavailable.invalidate()
        print("H264 partial-header boundary and invalid-stream diagnostic tests passed")
        let encoded = EncodedFrames()
        let opaque = Unmanaged.passUnretained(encoded).toOpaque()
        var session: VTCompressionSession?
        let status = VTCompressionSessionCreate(allocator: kCFAllocatorDefault,
            width: 160, height: 96, codecType: kCMVideoCodecType_H264,
            encoderSpecification: nil, imageBufferAttributes: nil,
            compressedDataAllocator: nil, outputCallback: { ref, _, status, _, sample in
                guard status == noErr, let ref, let sample else { return }
                Unmanaged<EncodedFrames>.fromOpaque(ref).takeUnretainedValue().append(sample)
            }, refcon: opaque, compressionSessionOut: &session)
        guard status == noErr, let session else {
            print("SKIPPED codec round-trip: host encoder unavailable (\(status))"); return
        }
        defer { VTCompressionSessionInvalidate(session) }
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: 10 as CFNumber)
        for index in 0..<24 {
            var image: CVPixelBuffer?
            precondition(CVPixelBufferCreate(kCFAllocatorDefault, 160, 96, kCVPixelFormatType_32ARGB,
                nil, &image) == kCVReturnSuccess)
            let buffer = image!
            CVPixelBufferLockBaseAddress(buffer, [])
            memset(CVPixelBufferGetBaseAddress(buffer), Int32(index * 8),
                   CVPixelBufferGetBytesPerRow(buffer) * 96)
            CVPixelBufferUnlockBaseAddress(buffer, [])
            precondition(VTCompressionSessionEncodeFrame(session, imageBuffer: buffer,
                presentationTimeStamp: CMTime(value: Int64(index), timescale: 30),
                duration: CMTime(value: 1, timescale: 30), frameProperties: nil,
                sourceFrameRefcon: nil, infoFlagsOut: nil) == noErr)
        }
        precondition(VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid) == noErr)
        var stream = encoded.data
        assert(!stream.isEmpty)
        stream.append(contentsOf: [0, 0, 0, 1, 9, 0xf0])
        let decoded = DecodedFrames()
        let decoder = H264StreamDecoder { decoded.receive($0) }
        // The first frame must decode with only the following AUD header available.
        let firstBoundary = stream.range(of: Data([0, 0, 0, 1, 9]))!.upperBound
        for offset in stride(from: 0, to: firstBoundary, by: 73) {
            await decoder.append(stream.subdata(in: offset..<min(offset + 73, firstBoundary)))
        }
        assert(decoded.total == 1)
        for offset in stride(from: firstBoundary, to: stream.count, by: 73) {
            await decoder.append(stream.subdata(in: offset..<min(offset + 73, stream.count)))
        }
        assert(decoded.total == 24)
        assert(decoder.hasDecodedFrame)
        decoder.invalidate()
        await decoder.append(stream)
        assert(decoded.total == 24)
        let packetFrames = DecodedFrames()
        let packetDecoder = H264StreamDecoder { packetFrames.receive($0) }
        for (index, unit) in encoded.accessUnits.enumerated() {
            await packetDecoder.appendAccessUnit(unit)
            assert(packetFrames.total == index + 1, "Complete picture waited for another packet")
        }
        assert(packetFrames.total == 24)
        packetDecoder.invalidate()
        await packetDecoder.appendAccessUnit(encoded.accessUnits[0])
        assert(packetFrames.total == 24)
        print("H264 raw stream and complete-access-unit round-trips passed: 24 frames each")
    }
}
