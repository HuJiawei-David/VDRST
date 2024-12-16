package org.example.springboot_1.entity;

import lombok.Data;

@Data
public class SavedResult {
    private int id;
    private int userId;
    private String sequence;
    private double similarityScore;
    private String classification;
    private String function;
}
