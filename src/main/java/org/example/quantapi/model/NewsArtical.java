package org.example.quantapi.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

@Document(collection = "news_articles")
public class NewsArticle {

    @Id
    private String id;
    private Map<String, String> source;
    private String title;
    private String description;
    private String content;
    private String url;
    private String publishedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Map<String, String> getSource() { return source; }
    public void setSource(Map<String, String> source) { this.source = source; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getPublishedAt() { return publishedAt; }
    public void setPublishedAt(String publishedAt) { this.publishedAt = publishedAt; }
}