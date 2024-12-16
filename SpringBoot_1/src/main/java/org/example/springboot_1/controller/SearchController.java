package org.example.springboot_1.controller;

import org.example.springboot_1.common.Result;
import org.example.springboot_1.dto.SearchRequest;
import org.example.springboot_1.entity.VirusMatch;
import org.example.springboot_1.service.VirusSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/search")
public class SearchController {

    @Autowired
    private VirusSearchService virusSearchService;

    @PostMapping
    public Result search(@RequestBody SearchRequest request) {
        try {
            String sequence = request.getSequence();
            if (sequence == null || sequence.isEmpty()) {
                return Result.error("400", "Sequence cannot be empty.");
            }
            List<VirusMatch> matches = virusSearchService.search(sequence);
            if (matches.isEmpty()) {
                return Result.success("No matching sequences found.");
            }
            return Result.success(matches);
        } catch (Exception e) {
            return Result.error("500", "Search failed: " + e.getMessage());
        }
    }
}



