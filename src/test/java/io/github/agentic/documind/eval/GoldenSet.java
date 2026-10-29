package io.github.agentic.documind.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads and sanity-checks the version-controlled golden set
 * ({@code src/test/resources/eval/golden-set.json}).
 *
 * <p>Structural checks only run here (unique ids, non-blank questions, known types).
 * Checks that need the ingested corpus — impossible page numbers, unknown filenames —
 * live in the harness, which knows the real page counts after ingestion. Problems are
 * <em>flagged</em>, never silently corrected.
 */
public final class GoldenSet {

	public static final String RESOURCE = "eval/golden-set.json";

	private static final Set<String> KNOWN_TYPES =
			Set.of("factual", "paraphrased", "should-return-nothing");

	private GoldenSet() {
	}

	/** Parse the golden set from the test classpath. */
	public static List<GoldenEntry> load() {
		ClassPathResource resource = new ClassPathResource(RESOURCE);
		try (InputStream in = resource.getInputStream()) {
			GoldenEntry[] entries = new ObjectMapper().readValue(in, GoldenEntry[].class);
			return List.of(entries);
		}
		catch (IOException e) {
			throw new IllegalStateException("Could not read golden set at classpath:" + RESOURCE, e);
		}
	}

	/** Structural warnings, independent of the corpus. Empty list means clean. */
	public static List<String> structuralWarnings(List<GoldenEntry> entries) {
		List<String> warnings = new ArrayList<>();
		Set<String> seenIds = new HashSet<>();
		for (GoldenEntry e : entries) {
			if (e.id() == null || e.id().isBlank()) {
				warnings.add("entry with blank id");
			}
			else if (!seenIds.add(e.id())) {
				warnings.add("duplicate id: " + e.id());
			}
			if (e.question() == null || e.question().isBlank()) {
				warnings.add(e.id() + ": blank question");
			}
			if (e.filename() == null || e.filename().isBlank()) {
				warnings.add(e.id() + ": blank filename");
			}
			if (!KNOWN_TYPES.contains(e.type())) {
				warnings.add(e.id() + ": unknown type '" + e.type() + "'");
			}
			boolean emptyPages = e.relevantPages().isEmpty();
			boolean nothingType = "should-return-nothing".equals(e.type());
			if (emptyPages != nothingType) {
				warnings.add(e.id() + ": type '" + e.type() + "' but relevantPages="
						+ e.relevantPages() + " (empty pages must mean should-return-nothing)");
			}
			for (Integer p : e.relevantPages()) {
				if (p == null || p < 1) {
					warnings.add(e.id() + ": relevantPages has non-positive page " + p);
				}
			}
		}
		return warnings;
	}
}
