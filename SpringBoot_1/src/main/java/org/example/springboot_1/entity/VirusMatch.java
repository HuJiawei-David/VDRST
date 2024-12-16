package org.example.springboot_1.entity;

import lombok.Data;

@Data
public class VirusMatch {
    private String matchedSequence;
    private double similarityScore;
    private String jobTitle; // 结果描述
}

