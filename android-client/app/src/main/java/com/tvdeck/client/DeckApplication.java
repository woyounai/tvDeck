package com.tvdeck.client;

import android.app.Application;

public final class DeckApplication extends Application {
    private DeckModel model;
    public synchronized DeckModel model() {
        if (model == null) model = new DeckModel(this);
        return model;
    }
}
