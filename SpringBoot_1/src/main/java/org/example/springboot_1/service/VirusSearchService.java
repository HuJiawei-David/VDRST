package org.example.springboot_1.service;

import org.example.springboot_1.entity.VirusMatch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class VirusSearchService {

    @Value("${blast.db.path}")
    private String blastDbPath;

    @Value("${blast.output.file}")
    private String blastOutputFile;

    @Value("${blast.executable}")
    private String blastExecutable;

    public List<VirusMatch> search(String sequence) {
        // 1. 将用户输入的序列写入 query.fasta 文件
        String queryFile = "./query.fasta";
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(queryFile))) {
                writer.write(">UserInput\n");
                writer.write(sequence.trim());
                writer.write("\n");
            }

            // 2. 执行本地BLAST命令
            String[] cmd = {
                    blastExecutable,
                    "-query", queryFile,
                    "-db", blastDbPath,
                    "-out", blastOutputFile,
                    "-evalue", "100",
                    "-task", "blastn-short",
                    "-word_size", "4",
                    "-reward", "1",
                    "-penalty", "-2",
                    "-gapopen", "2",
                    "-gapextend", "2",
                    "-max_target_seqs", "10",
                    "-max_hsps", "5"
            };

            Process process = Runtime.getRuntime().exec(cmd);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Local BLAST execution failed, exit code: " + exitCode);
            }

            // 3. 读取 BLAST 结果
            StringBuilder rawBlastResult = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(blastOutputFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    rawBlastResult.append(line).append("\n");
                }
            }

            // 4. 解析 BLAST 结果为Candidates
            List<Candidate> candidates = parseBlastOutput(rawBlastResult.toString());

            // 5. 使用Smith-Waterman对每个candidate进行精细比对得分（百分制）
            String querySequence = sequence.trim();
            for (Candidate candidate : candidates) {
                // 合并所有HSP的序列
                StringBuilder combinedQuery = new StringBuilder();
                StringBuilder combinedSubject = new StringBuilder();
                for (HSP hsp : candidate.hsps) {
                    combinedQuery.append(hsp.querySeq);
                    combinedSubject.append(hsp.sbjctSeq);
                }

                // 如果没有HSP，跳过
                if (combinedQuery.length() == 0 || combinedSubject.length() == 0) {
                    candidate.similarityScore = 0;
                    continue;
                }

                int rawScore = runSmithWaterman(combinedQuery.toString(), combinedSubject.toString());

                // 使用较短序列长度计算最大可能得分(假设match=2)
                int minLen = Math.min(combinedQuery.length(), combinedSubject.length());
                int maxPossibleScore = minLen * 2; // matchScore=2的情况下最大可能得分为2*长度
                int percentageScore = (maxPossibleScore == 0) ? 0 : (int)(((double)rawScore / maxPossibleScore) * 100);
                candidate.similarityScore = percentageScore;
            }

            // 6. 按Smith-Waterman得分(百分制)降序排序，并取前3
            candidates.sort((a, b) -> Integer.compare(b.similarityScore, a.similarityScore));
            List<Candidate> topCandidates = candidates.subList(0, Math.min(3, candidates.size()));

            // 7. 转换为VirusMatch对象返回
            List<VirusMatch> matches = new ArrayList<>();
            for (Candidate c : topCandidates) {
                VirusMatch match = new VirusMatch();
                match.setMatchedSequence(c.description);
                match.setSimilarityScore(c.similarityScore);

                // 格式化 alignmentDetails 为理想格式
                String fullDetails = "序列:\n"
                        + "Length=" + (c.hsps.isEmpty() ? 0 : c.hsps.get(0).sbjctSeq.length()) + "\n"
                        + c.alignmentDetails.toString();
                match.setJobTitle(fullDetails);
                matches.add(match);
            }

            return matches;

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Search process failed: " + e.getMessage(), e);
        }
    }

    // 内部类 Candidate
    private static class Candidate {
        String description;
        List<HSP> hsps = new ArrayList<>();
        StringBuilder alignmentDetails = new StringBuilder();
        int similarityScore;

        Candidate(String desc) {
            this.description = desc;
            this.similarityScore = 0;
        }
    }

    // HSP存储Query片段和Sbjct片段
    private static class HSP {
        String querySeq;
        String sbjctSeq;

        HSP(String q, String s) {
            this.querySeq = q;
            this.sbjctSeq = s;
        }
    }

    /**
     * 解析 BLAST 输出，并在对齐结果中加入匹配行(|)
     */
    private List<Candidate> parseBlastOutput(String blastOutput) {
        List<Candidate> candidates = new ArrayList<>();
        String[] lines = blastOutput.split("\n");
        Candidate current = null;

        String lastScoreLine = null;
        String lastQueryLine = null;
        String lastQuerySeq = null;
        String lastSbjctLine = null;
        String lastSbjctSeq = null;
        String queryPrefixSpaces = "";

        for (String rawLine : lines) {
            String line = rawLine.trim();

            // 遇到 ">" 表示一个新的candidate开始
            if (line.startsWith(">")) {
                // 将前一个candidate保存
                if (current != null) {
                    // 如果有未保存的HSP，则保存它
                    if (lastQueryLine != null && lastSbjctLine != null && lastQuerySeq != null && lastSbjctSeq != null) {
                        current.hsps.add(new HSP(lastQuerySeq, lastSbjctSeq));
                    }
                    candidates.add(current);
                }
                current = new Candidate(line.substring(1).trim());
                lastScoreLine = null;
                lastQueryLine = null;
                lastQuerySeq = null;
                lastSbjctLine = null;
                lastSbjctSeq = null;
                queryPrefixSpaces = "";
                continue;
            }

            if (current == null) {
                // 在遇到第一个 ">" 之前的行忽略
                continue;
            }

            if (line.matches("^Score =.*")) {
                // 保存上一个HSP（如果有）
                if (lastQueryLine != null && lastSbjctLine != null && lastQuerySeq != null && lastSbjctSeq != null) {
                    current.hsps.add(new HSP(lastQuerySeq, lastSbjctSeq));
                }
                current.alignmentDetails.append("\n").append(line).append("\n");
                lastScoreLine = line;
                lastQueryLine = null;
                lastQuerySeq = null;
                lastSbjctLine = null;
                lastSbjctSeq = null;
                queryPrefixSpaces = "";
            } else if (line.startsWith("Query")) {
                lastQueryLine = line;
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    lastQuerySeq = parts[2];
                    int seqIndex = line.indexOf(lastQuerySeq);
                    queryPrefixSpaces = line.substring(0, seqIndex).replaceAll("[^\\s]", " ");
                } else {
                    lastQuerySeq = "";
                }
                current.alignmentDetails.append(line).append("\n");
            } else if (line.startsWith("Sbjct")) {
                lastSbjctLine = line;
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    lastSbjctSeq = parts[2];
                } else {
                    lastSbjctSeq = "";
                }

                // 构建匹配行
                StringBuilder matchLine = new StringBuilder();
                matchLine.append(queryPrefixSpaces);
                int length = Math.min(lastQuerySeq.length(), lastSbjctSeq.length());
                for (int i = 0; i < length; i++) {
                    if (lastQuerySeq.charAt(i) == lastSbjctSeq.charAt(i)) {
                        matchLine.append("|");
                    } else {
                        matchLine.append(" ");
                    }
                }

                current.alignmentDetails.append(matchLine.toString()).append("\n");
                current.alignmentDetails.append(line).append("\n");
            } else {
                // 其他行暂不处理
            }
        }

        // 文件结束时，如果还有最后一个candidate需要保存
        if (current != null) {
            if (lastQueryLine != null && lastSbjctLine != null && lastQuerySeq != null && lastSbjctSeq != null) {
                current.hsps.add(new HSP(lastQuerySeq, lastSbjctSeq));
            }
            candidates.add(current);
        }

        return candidates;
    }

    /**
     * Smith-Waterman局部比对算法实现（简化示例）
     */
    private int runSmithWaterman(String query, String subject) {
        int matchScore = 2;
        int mismatchScore = -1;
        int gapPenalty = -1;

        int qlen = query.length();
        int slen = subject.length();
        int[][] H = new int[qlen + 1][slen + 1];

        int maxScore = 0;
        for (int i = 1; i <= qlen; i++) {
            for (int j = 1; j <= slen; j++) {
                char qChar = query.charAt(i - 1);
                char sChar = subject.charAt(j - 1);
                int scoreDiag = H[i - 1][j - 1] + (qChar == sChar ? matchScore : mismatchScore);
                int scoreDel = H[i - 1][j] + gapPenalty;
                int scoreIns = H[i][j - 1] + gapPenalty;
                H[i][j] = Math.max(0, Math.max(scoreDiag, Math.max(scoreDel, scoreIns)));
                if (H[i][j] > maxScore) {
                    maxScore = H[i][j];
                }
            }
        }

        return maxScore;
    }
}







