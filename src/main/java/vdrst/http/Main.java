package vdrst.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import vdrst.align.ShortGotohAligner;
import vdrst.index.GenomeStore;
import vdrst.index.KmerIndex;
import vdrst.index.KmerPrefilter;
import vdrst.index.Prefilter;
import vdrst.service.Match;
import vdrst.service.SearchService;
import vdrst.service.SequenceValidator;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * The HTTP entry point, on {@code com.sun.net.httpserver} from the JDK.
 *
 * <p>v1 ran on Spring Boot 2.6.13 with roughly forty transitive dependencies, of which
 * iText 5.5.8 and POI 5.2.3 carried known vulnerabilities, to serve one endpoint that
 * mattered. This file is the whole web layer. It is not a claim that Spring is the wrong
 * tool in general — it is that a service with one route and no persistence was never the
 * case for it.
 *
 * <p>The index is built once at startup and shared by every request thread. Building it
 * costs a few seconds and tens of megabytes; doing it per request would be v1's mistake
 * in a new place.
 */
public final class Main {

    private static final int DEFAULT_PORT = 9090;

    public static void main(String[] args) throws IOException {
        Path database = Paths.get(argument(args, "--db", "bench/corpus/viruses.fasta"));
        int port = Integer.parseInt(argument(args, "--port", System.getenv().getOrDefault(
                "VDRST_PORT", String.valueOf(DEFAULT_PORT))));
        int k = Integer.parseInt(argument(args, "--k", String.valueOf(KmerIndex.DEFAULT_K)));
        int stride = Integer.parseInt(argument(args, "--stride", "1"));

        requireFasta(database);

        System.out.println("VDRST — loading " + database);
        long started = System.nanoTime();
        GenomeStore store = GenomeStore.load(database);

        // Roughly 1 byte per base for the sequences, 4 per indexed position, plus the
        // bucket table. Said out loud before it is allocated, because the alternative is
        // an OutOfMemoryError three minutes into startup with no explanation.
        long estimate = store.totalBases() + (4L * store.totalBases() / stride)
                + (4L << (2 * k));
        System.out.printf("  %,d genomes, %,d bases — index needs about %,d MB (k=%d, stride=%d)%n",
                store.count(), store.totalBases(), estimate / 1048576, k, stride);
        if (store.ambiguityCodes() > 0) {
            System.out.printf("  %,d IUPAC ambiguity bases folded to N%n", store.ambiguityCodes());
        }
        if (estimate > Runtime.getRuntime().maxMemory()) {
            System.out.printf("  heap is %,d MB. Raise it with -Xmx, or halve the index with --stride 2.%n",
                    Runtime.getRuntime().maxMemory() / 1048576);
        }

        KmerIndex index = KmerIndex.build(store, k, stride);
        System.out.printf("  ready in %.1f s, index %,.0f MB%n",
                (System.nanoTime() - started) / 1e9, index.approximateBytes() / 1048576.0);
        System.out.println("  vectors: " + ShortGotohAligner.speciesDescription());

        Prefilter prefilter = new KmerPrefilter(index);
        SearchService service = new SearchService(prefilter);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/search", exchange -> handleSearch(exchange, service));
        server.createContext("/health", Main::handleHealth);
        server.createContext("/", Main::handleIndex);

        // Virtual threads: a request spends its time in alignment, and the platform-thread
        // pool sizing that would otherwise need tuning stops being a question.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.out.println("  listening on http://localhost:" + port);
    }

    private static void handleSearch(HttpExchange exchange, SearchService service) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String sequence = Json.readString(body, "sequence");
            if (sequence == null) {
                respond(exchange, 400, "{\"error\":\"missing field \\\"sequence\\\"\"}");
                return;
            }

            long started = System.nanoTime();
            List<Match> matches = service.search(sequence);
            double millis = (System.nanoTime() - started) / 1e6;

            respond(exchange, 200, render(matches, millis));

        } catch (SequenceValidator.InvalidRequestException | Json.MalformedJsonException e) {
            respond(exchange, 400, "{\"error\":" + Json.quote(e.getMessage()) + "}");
        } catch (RuntimeException e) {
            // The message may name internal paths, so it is logged rather than returned.
            System.err.println("search failed: " + e);
            respond(exchange, 500, "{\"error\":\"search failed\"}");
        }
    }

    private static String render(List<Match> matches, double millis) {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"elapsedMs\":").append(String.format("%.3f", millis)).append(",\"matches\":[");
        for (int i = 0; i < matches.size(); i++) {
            Match m = matches.get(i);
            if (i > 0) out.append(',');
            out.append("{\"subjectId\":").append(Json.quote(m.subjectId()))
               .append(",\"title\":").append(Json.quote(m.title()))
               .append(",\"subjectLength\":").append(m.subjectLength())
               .append(",\"alignmentScore\":").append(m.alignmentScore())
               .append(",\"normalizedScore\":").append(String.format("%.4f", m.normalizedScore()))
               .append(",\"subjectOffset\":").append(m.subjectOffset())
               .append(",\"seedHits\":").append(m.seedHits())
               .append('}');
        }
        return out.append("]}").toString();
    }

    private static void handleHealth(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"status\":\"ok\"}");
    }

    private static void handleIndex(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            respond(exchange, 404, "{\"error\":\"not found\"}");
            return;
        }
        byte[] page = PAGE.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, page.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(page);
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String argument(String[] args, String flag, String fallback) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(flag)) return args[i + 1];
        return fallback;
    }

    /**
     * v2 reads FASTA. A BLAST database is a set of binary index files, and pointing at one
     * is the single most likely way to get this wrong — the path looks right, and the
     * files are right there next to it. So say what to run instead of failing with
     * NoSuchFileException.
     */
    private static void requireFasta(Path database) {
        if (java.nio.file.Files.exists(database)) return;

        boolean looksLikeBlastDb = java.nio.file.Files.exists(Paths.get(database + ".nin"))
                || java.nio.file.Files.exists(Paths.get(database + ".nsq"));

        if (looksLikeBlastDb) {
            throw new IllegalArgumentException("""
                %s is a BLAST database, not a FASTA file.

                v2 has no BLAST dependency, so it reads sequences directly. Export once:

                    blastdbcmd -db %s -entry all -out %s.fasta

                then start with --db %s.fasta
                """.formatted(database, database, database, database));
        }
        throw new IllegalArgumentException("no such file: " + database
                + "\n\nGenerate the sample database with:  make corpus");
    }

    /**
     * The interface, inlined. v1's frontend was a Vue application in a second repository,
     * carried as a submodule, calling endpoints that no longer exist. For one text box and
     * a results table that was always more machinery than the job needed.
     */
    private static final String PAGE = """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>VDRST</title>
        <style>
          :root { color-scheme: light dark; --fg:#1a1a1a; --bg:#fbfbfa; --dim:#6b6b6b;
                  --line:#e0dedb; --accent:#2f6f4f; --card:#fff; }
          @media (prefers-color-scheme: dark) {
            :root { --fg:#e8e6e3; --bg:#191918; --dim:#9a9895; --line:#33322f;
                    --accent:#7fbf9a; --card:#211f1e; }
          }
          * { box-sizing: border-box; }
          body { margin:0; padding:2.5rem 1.5rem; background:var(--bg); color:var(--fg);
                 font:16px/1.6 ui-sans-serif,-apple-system,"Segoe UI",sans-serif; }
          main { max-width:52rem; margin:0 auto; }
          h1 { font-size:1.5rem; margin:0 0 .25rem; letter-spacing:-.01em; }
          p.sub { color:var(--dim); margin:0 0 2rem; font-size:.9rem; }
          textarea { width:100%; min-height:8rem; padding:.85rem; border:1px solid var(--line);
                     border-radius:8px; background:var(--card); color:var(--fg); resize:vertical;
                     font:13px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace; }
          .row { display:flex; gap:.75rem; align-items:center; margin-top:.85rem; flex-wrap:wrap; }
          button { padding:.6rem 1.4rem; border:0; border-radius:8px; background:var(--accent);
                   color:#fff; font-size:.9rem; font-weight:500; cursor:pointer; }
          button:disabled { opacity:.5; cursor:default; }
          .timing { color:var(--dim); font-size:.85rem; }
          table { width:100%; border-collapse:collapse; margin-top:1.75rem; font-size:.9rem; }
          th { text-align:left; font-weight:500; color:var(--dim); font-size:.78rem;
               text-transform:uppercase; letter-spacing:.04em; padding:.5rem .6rem;
               border-bottom:1px solid var(--line); }
          td { padding:.65rem .6rem; border-bottom:1px solid var(--line); }
          td.num { text-align:right; font-variant-numeric:tabular-nums;
                   font-family:ui-monospace,SFMono-Regular,Menlo,monospace; }
          .bar { height:4px; border-radius:2px; background:var(--accent); min-width:2px; }
          .err { color:#c0392b; margin-top:1rem; font-size:.9rem; }
          .note { color:var(--dim); font-size:.82rem; }
          .caveat { color:var(--dim); font-size:.82rem; margin-top:.9rem; }
          @media (prefers-color-scheme: dark) { .err { color:#e08e84; } }
        </style>
        </head>
        <body>
        <main>
          <h1>VDRST</h1>
          <p class="sub">Paste a DNA or RNA sequence. A k-mer index narrows the database,
             then each candidate is re-aligned exactly with a vectorised Smith-Waterman.<br>
             <span class="note">Scores are alignment scores, not E-values — a short query
             finds chance matches in a database this size. Use a few hundred bases.</span></p>

          <textarea id="q" spellcheck="false"
            placeholder="ACGTACGTACGT&#10;&#10;FASTA headers are fine — they are stripped."></textarea>
          <div class="row">
            <button id="go">Search</button>
            <span class="timing" id="t"></span>
          </div>
          <div class="err" id="e"></div>
          <div id="out"></div>
        </main>
        <script>
        const $ = id => document.getElementById(id);
        async function search() {
          const sequence = $('q').value.trim();
          $('e').textContent = ''; $('out').innerHTML = ''; $('t').textContent = '';
          if (!sequence) { $('e').textContent = 'Enter a sequence first.'; return; }
          $('go').disabled = true;
          try {
            const res = await fetch('/search', {
              method: 'POST', headers: {'Content-Type': 'application/json'},
              body: JSON.stringify({sequence})
            });
            const data = await res.json();
            if (!res.ok) { $('e').textContent = data.error || 'Search failed.'; return; }
            $('t').textContent = data.elapsedMs.toFixed(2) + ' ms server-side';
            const shortQuery = sequence.replace(/[^A-Za-z]/g, '').length < 100;
            if (!data.matches.length) { $('e').textContent = 'No matches.'; return; }
            $('out').innerHTML =
              '<table><thead><tr><th>Sequence</th><th style="text-align:right">Score</th>' +
              '<th style="text-align:right" title="Alignment score as a fraction of the best this ' +
              'query could score against a perfect copy of itself. Not sequence identity.">' +
              'of max</th><th style="width:28%">&nbsp;</th></tr></thead><tbody>' +
              data.matches.map(m => {
                const pct = (m.normalizedScore * 100).toFixed(1);
                return '<tr><td>' + esc(m.subjectId) +
                  '<div style="color:var(--dim);font-size:.8rem">' + esc(m.title) + '</div></td>' +
                  '<td class="num">' + m.alignmentScore + '</td>' +
                  '<td class="num">' + pct + '%</td>' +
                  '<td><div class="bar" style="width:' + Math.max(2, m.normalizedScore * 100) + '%"></div></td></tr>';
              }).join('') + '</tbody></table>' +
              (shortQuery ? '<p class="caveat">This query is short. In a database of this size ' +
                'a random sequence of the same length scores similarly, so treat these as ' +
                'candidates rather than findings.</p>' : '');
          } catch (err) {
            $('e').textContent = 'Could not reach the server.';
          } finally {
            $('go').disabled = false;
          }
        }
        function esc(s) { const d = document.createElement('div'); d.textContent = s; return d.innerHTML; }
        $('go').addEventListener('click', search);
        $('q').addEventListener('keydown', e => { if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') search(); });
        </script>
        </body>
        </html>
        """;
}
