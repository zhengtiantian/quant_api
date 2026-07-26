package org.example.quantapi.controller;

import org.example.quantapi.model.NewsArticle;
import org.example.quantapi.repository.NewsArticleRepository;
import org.example.quantapi.service.NewsSearchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsArticleRepository repository;
    private final NewsSearchService searchService;

    public NewsController(NewsArticleRepository repository, NewsSearchService searchService) {
        this.repository = repository;
        this.searchService = searchService;
    }

    @GetMapping
    public List<NewsArticle> getLatestNews() {
        return repository.findTop30ByOrderByPublishedAtDesc();
    }

    /**
     * Full-text search over the LLM-labeled corpus in quant_data.
     *
     * <p>Separate from {@link #getLatestNews()}, which reads the raw feed in the default
     * database. Results carry the merged sentiment label and a bounded excerpt.
     *
     * @param q      keywords; omit to list a symbol's coverage by date
     * @param symbol optional ticker filter
     * @param from   optional inclusive lower bound, YYYYMMDD or YYYY-MM-DD
     * @param to     optional inclusive upper bound
     * @param limit  capped server-side at 50
     */
    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(name = "q", required = false, defaultValue = "") String q,
            @RequestParam(name = "symbol", required = false) String symbol,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return searchService.search(q, symbol, from, to, limit);
    }
}