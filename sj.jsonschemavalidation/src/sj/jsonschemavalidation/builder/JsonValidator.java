package sj.jsonschemavalidation.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;

/**
 * Pure JSON validation core, free of any Eclipse dependencies so it can be unit
 * tested directly.
 *
 * <p>It parses a JSON document, optionally validates it against a JSON schema and
 * returns a list of {@link Problem}s. Each problem carries the 1-based line number
 * in the source document (best effort) and the message. The Eclipse-facing code in
 * {@link ValidateJson} turns these into problem markers.
 */
public class JsonValidator {

	/** A single validation problem, mapped to a line in the source document. */
	public static final class Problem {
		private final String pointer;
		private final int lineNumber;
		private final String message;

		public Problem(String pointer, int lineNumber, String message) {
			this.pointer = pointer;
			this.lineNumber = lineNumber;
			this.message = message;
		}

		/** JSON pointer of the offending instance location, e.g. {@code /Products/1/Name}. */
		public String getPointer() {
			return pointer;
		}

		/** 1-based line number in the validated document (1 when unknown). */
		public int getLineNumber() {
			return lineNumber;
		}

		public String getMessage() {
			return message;
		}

		@Override
		public String toString() {
			return "line " + lineNumber + " " + pointer + ": " + message;
		}
	}

	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Validates the given JSON document, optionally against the given schema.
	 *
	 * @param json       the JSON document to validate (never {@code null})
	 * @param schema     the JSON schema, or {@code null} to only check that the
	 *                   document is well-formed JSON
	 * @return the problems found; empty when the document is valid
	 */
	public List<Problem> validate(String json, String schema) {
		List<Problem> problems = new ArrayList<>();

		JsonNode root;
		try {
			root = mapper.readTree(json);
		} catch (JsonParseException parseException) {
			// not well-formed JSON: report syntax error at its location
			JsonLocation where = parseException.getLocation();
			int line = where != null && where.getLineNr() > 0 ? where.getLineNr() : 1;
			problems.add(new Problem("", line, parseException.getOriginalMessage()));
			return problems;
		} catch (Exception e) {
			problems.add(new Problem("", 1, e.getMessage()));
			return problems;
		}

		// No schema? Then well-formedness (checked above) is all that matters.
		if (schema == null) {
			return problems;
		}

		final JsonNode schemaJson;
		try {
			schemaJson = mapper.readTree(schema);
		} catch (Exception e) {
			problems.add(new Problem("", 1, "Invalid schema: " + e.getMessage()));
			return problems;
		}

		// Pick the schema draft the schema declares (its $schema dialect), defaulting
		// to draft-07 which matches the behaviour of the previously used validator.
		SpecificationVersion dialect = detectDialect(schemaJson);

		// Report instance locations as JSON pointers (e.g. /Products/5/Name) so they
		// line up with the pointers produced by JsonLineNumbers.
		final SchemaRegistryConfig config = SchemaRegistryConfig.builder()
				.pathType(PathType.JSON_POINTER)
				.build();
		final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(dialect,
				builder -> builder.schemaRegistryConfig(config));
		final Schema compiled = registry.getSchema(schemaJson);

		final List<Error> errors = compiled.validate(root);
		if (errors.isEmpty()) {
			return problems;
		}

		final Map<String, Integer> lineNumbersByJsonPointer = JsonLineNumbers.handleString(json);
		for (Error error : errors) {
			String pointer = error.getInstanceLocation().toString();
			int lineNo = lineNumbersByJsonPointer.getOrDefault(pointer, 1);
			problems.add(new Problem(pointer, lineNo, error.getMessage()));
		}
		return problems;
	}

	/**
	 * Determines the JSON Schema draft to use, based on the schema's {@code $schema}
	 * value. Falls back to draft-07 when there is no (recognized) {@code $schema}.
	 */
	private static SpecificationVersion detectDialect(JsonNode schemaJson) {
		JsonNode dialectNode = schemaJson.get("$schema");
		if (dialectNode != null && dialectNode.isTextual()) {
			String dialectId = dialectNode.asText();
			// tolerate a trailing '#'
			Optional<SpecificationVersion> version = SpecificationVersion.fromDialectId(dialectId);
			if (version.isEmpty() && dialectId.endsWith("#")) {
				version = SpecificationVersion.fromDialectId(dialectId.substring(0, dialectId.length() - 1));
			}
			if (version.isPresent()) {
				return version.get();
			}
		}
		return SpecificationVersion.DRAFT_7;
	}
}
