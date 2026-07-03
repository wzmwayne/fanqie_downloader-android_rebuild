package com.example.fqdownloader.model;

public class ImageInfo {
    public final byte[] data;
    public final String mime;
    public final String filename;

    public ImageInfo(byte[] data, String mime, String filename) {
        this.data = data;
        this.mime = mime;
        this.filename = filename;
    }
}
