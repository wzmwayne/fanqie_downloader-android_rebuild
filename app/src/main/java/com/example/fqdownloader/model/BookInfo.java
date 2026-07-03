package com.example.fqdownloader.model;

public class BookInfo {
    public final String bookId;
    public final String title;
    public final String author;
    public final String category;
    public final String score;
    public final String description;

    public BookInfo(String bookId, String title, String author, String category, String score, String description) {
        this.bookId = bookId;
        this.title = title;
        this.author = author;
        this.category = category;
        this.score = score;
        this.description = description;
    }
}
