package org.example.quantapi.repository;

import org.example.quantapi.model.NewsArticle;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface NewsArticleRepository extends MongoRepository<NewsArticle, String> {
    List<NewsArticle> findTop30ByOrderByPublishedAtDesc();
}