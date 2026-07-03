package com.example.fqdownloader.model;

public class Chapter {
    public final String itemId;
    public final String title;
    public final int order;

    public Chapter(String itemId, String title, int order) {
        this.itemId = itemId;
        this.title = title;
        this.order = order;
    }
}
