package org.example.quantapi.controller;

import org.example.quantapi.model.NewsArticle;
import org.example.quantapi.repository.NewsArticleRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsArticleRepository repository;

    public NewsController(NewsArticleRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<NewsArticle> getLatestNews() {
        return repository.findTop30ByOrderByPublishedAtDesc();
    }
}