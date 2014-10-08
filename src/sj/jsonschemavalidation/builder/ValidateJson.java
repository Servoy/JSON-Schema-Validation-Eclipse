package sj.jsonschemavalidation.builder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import sj.jsonschemavalidation.ISchemaProvider;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.fge.jackson.JsonLoader;
import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import com.github.fge.jsonschema.core.report.ProcessingMessage;
import com.github.fge.jsonschema.core.report.ProcessingReport;
import com.github.fge.jsonschema.main.JsonSchema;
import com.github.fge.jsonschema.main.JsonSchemaFactory;


public class ValidateJson {
	private static final String MARKER_TYPE = "sj.jsonschemavalidation.jsonProblem";
	private static Map<IResource, List<IFile>> datafileBySchema = new HashMap<IResource, List<IFile>>();
	private static final Logger logger = Logger.getAnonymousLogger();
	
	// remove all our eclipse error markers from file
	private static void deleteMarkers(IFile file) {
		try {
			int nMarkers = file.findMarkers(MARKER_TYPE, false, IResource.DEPTH_ZERO).length;
			logger.fine("Removing old markers: " + nMarkers);
			file.deleteMarkers(MARKER_TYPE, false, IResource.DEPTH_ZERO);
		} catch (CoreException ce) {
		}
	}

	/*
	 * Finds and returns the member resource identified by the given path in this container, or null if no such resource exists. 
	 * Unlike org.eclipse.core.resources.IContainer.findMember It is not case sensitive.
	 */
	private static IResource findMemberIgnoreCase(IContainer container, String name) {
		try {
			for (IResource res : container.members()) {
				if (res.getName().equalsIgnoreCase(name)) {
					return res;
				}
			}
		} catch (CoreException e) {
			logger.warning(e.toString());
		}
		return null;
	}
	
	
	/* Look for a schema that can be applied to the given file.	  
	 * matches by name. Foo.json -> FooSchema.json etc.
	 * Returns null, if no schema found 
	 */
	private static IResource getSchemaResource(IFile file) {
		final String filename = file.getName();
		final String extension = file.getFileExtension();
		final String basename = file.getName().substring(0, filename.length() - 1 - extension.length());
		
		// no schemas for schemas
		if(filename.toLowerCase().endsWith("schema.json")) {
			return null;
		}
		
		// possible file names of a schema
		final String[] candidates = {
				basename + "Schema" + ".json",
				basename + ".schema" + ".json",
				"schema.json",
		};
		
		// try to find schema
		for (final String wantedSchemaFileName : candidates) {
			logger.fine("Looking for \"" + wantedSchemaFileName + "\" in \"" + file.getParent() + "\"");
			
			// IResource schemaFile = file.getParent().findMember(wantedSchemaFileName);
			IResource schemaFile = findMemberIgnoreCase(file.getParent(), wantedSchemaFileName);
			if (schemaFile != null && schemaFile instanceof IFile) {
				// Add schema->datafile mapping to index
				if (! datafileBySchema.containsKey(schemaFile)) {
					datafileBySchema.put(schemaFile, new ArrayList<IFile>());
				}
				List<IFile> datafileList = datafileBySchema.get(schemaFile);
				if (!datafileList.contains(file)) {
					datafileList.add(file); 
				}
				return schemaFile;
			}
		}
		
		// no schema found
		return null;
	}
	

	
	
	// Add eclipse error marker
	private static void addMarker(IFile file, String message, int lineNumber,
			int severity) {
		try {
			IMarker marker = file.createMarker(MARKER_TYPE);
			marker.setAttribute(IMarker.MESSAGE, message);
			marker.setAttribute(IMarker.SEVERITY, severity);
			if (lineNumber == -1) {
				lineNumber = 1;
			}
			marker.setAttribute(IMarker.LINE_NUMBER, lineNumber);
			logger.fine("New marker: Line " + lineNumber + " for " + marker.getResource() + ", msg=" + marker.getAttribute(IMarker.MESSAGE, "(none)"));
		} catch (CoreException e) {
			e.printStackTrace();
		}
	}
	
	
	private static String readFile(IFile file) throws IOException, CoreException {
		InputStreamReader reader = new InputStreamReader(file.getContents());
		BufferedReader in = new BufferedReader(reader);
		String line;
		StringBuilder all = new StringBuilder();
		while((line = in.readLine()) != null) {
			all.append(line).append('\n');
		}
		return all.toString();
	}
	
	/* Validate JSON file
	 * 
	 * Looks for schema file by name @see getSchemaResource
	 */
	public static void checkResource(IResource resource) {
		// JSON file?
		if (resource instanceof IFile) {
			IFile file = (IFile) resource;
			if (!file.exists()) {
				logger.fine("File removed: " + file.getName());
				return;
			}
			
			try {
				String schemaString = getSchemaByExtension(file);
				if (schemaString != null) {
					// validate
					deleteMarkers(file);
					checkAgainst(file, schemaString);
						
				}
				// has schema?
				else if (resource.getName().endsWith(".json")) {
					IResource schemaResource = getSchemaResource(file);
					if (schemaResource != null && schemaResource instanceof IFile) {
						IFile schemaFile = (IFile) schemaResource;
						
						// validate
						deleteMarkers(file);
						checkAgainst(file, schemaFile);
							
					} else {
						// is schema?
						IFile schemaOrDataFile = (IFile) resource;
						List<IFile> dependent = datafileBySchema.get(resource);
						if (dependent != null && dependent.size() > 0) {
							for (IFile datafile : dependent) {
								logger.fine(datafile  + " status affected by schema " + schemaOrDataFile);
								deleteMarkers(datafile);
								checkAgainst(datafile, schemaOrDataFile);
							}
						} else {
							// Has, and is, no schema? check syntax
							logger.fine("No Schema for " + file.toString());
							deleteMarkers(schemaOrDataFile);
							checkAgainst(schemaOrDataFile, (String)null);
						}
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			} catch (CoreException e) {
				e.printStackTrace();
			} catch (ProcessingException e) {
				e.printStackTrace();
			}
		}
	}


	private static String getSchemaByExtension(IFile file) {
		List<ISchemaProvider> schemaProviders = Activator.getDefault().getSchemaProviders();
		for (ISchemaProvider schemaProvider : schemaProviders) {
			String schema = schemaProvider.getSchemaFor(file);
			if (schema != null) return schema;
		}
		return null;
	}

	/* add marker for given resource file using parse exception. */ 
	private static void handleParseException(IFile file, JsonParseException parseException) {
		// ignore, if syntax is already checked by JSON Editor
		IProject proj = file.getProject();
		if (ProjectProperties.hasValidationNature(proj)) {
			logger.info("No marker for \"" + file.getName() + "\" since \"" + proj.getName() + "\" is checked by JSON Editor.");
			return;
		}
		
		JsonLocation where = parseException.getLocation();
		addMarker(file, parseException.getOriginalMessage(), where.getLineNr(), IMarker.SEVERITY_ERROR);
	}

	private static void checkAgainst(IFile file, IFile schemaFile)
			throws IOException, CoreException, ProcessingException {
		String schemaString = null;
		// No schema? Use empty schema to get syntax messages.
		if (schemaFile != null) {
			schemaString = readFile(schemaFile);
		}

		checkAgainst(file, schemaString);
	}

	/**
	 * @param file
	 * @param schemaString
	 * @throws IOException
	 * @throws CoreException
	 * @throws ProcessingException
	 */
	private static void checkAgainst(IFile file, String schemaString)
			throws IOException, CoreException, ProcessingException {
		String where = " (" + file.getName() + ". Schema: " + 
			(schemaString == null ? "(none)" : schemaString) + ")"; 
		// File ioFile = new File(file.getLocation().toPortableString());				
		// JsonNode root = JsonLoader.fromFile(ioFile);
		
		final String all = readFile(file);
		
		JsonNode root;
		
		try {
			root = JsonLoader.fromString(all);
		} catch (JsonParseException parseException) {
			// not well-formed JSON
			handleParseException(file, parseException);
			return;
		}
		
		// .getLocation().toPortableString()
		JsonNode schemaJson = JsonLoader.fromString("{}");
		if (schemaString != null) {
			schemaJson = JsonLoader.fromString(schemaString);
		}
		final JsonSchemaFactory factory = JsonSchemaFactory.byDefault();

		final JsonSchema schema = factory.getJsonSchema(schemaJson);

		ProcessingReport report;

		report = schema.validate(root);
		logger.finer(report + where);
		
		
		Iterator<ProcessingMessage> iterator = report.iterator();
		// add markers
		if (iterator.hasNext()) {
			Map<String, Integer> lineNumbersByJsonPointer = JsonLineNumbers
					.handleString(all);
			while (iterator.hasNext()) {
				ProcessingMessage pm = iterator.next();
				String msg = pm.getMessage();
				logger.fine(msg + where);
				int lineNo = 1;
				JsonNode json = pm.asJson();
				JsonNode reports = json.get("reports");
				if (reports != null) {
					Iterator<JsonNode> elements = reports.elements();
					while (elements.hasNext()) {
						JsonNode reportArray = elements.next();
						Iterator<JsonNode> elemts2 = reportArray.elements();
						while (elemts2.hasNext()) {
							JsonNode jsonNode = elemts2.next().get("message");
							msg += "\n\t" + ((TextNode) jsonNode).asText();
						}
						if (elements.hasNext())
							msg += "\nor";
					}
					reports.getNodeType();
				}
				String pointer = ((TextNode) json.get("instance")
						.get("pointer")).asText();
				if (lineNumbersByJsonPointer.containsKey(pointer)) {
					lineNo = lineNumbersByJsonPointer.get(pointer);
				} else if (!"".equals(pointer)){
					logger.warning("Unknown line number of \"" + pointer + "\"");
				}
				addMarker(file, msg, lineNo, IMarker.SEVERITY_ERROR);
			}
		}
	}


	/** Handles removed resources */
	public static void removeResource(IResource resource) {
		// iterate over schema->data mapping
		for (IResource schemaFile : datafileBySchema.keySet()) {
			// Removed res is a schema file?
			if (resource.equals(schemaFile)) {
				// remove mappings for schema
				List<IFile> dependent = datafileBySchema.get(schemaFile);
				datafileBySchema.remove(schemaFile);
				
				// look for affected data files
				for (IFile datafile : dependent) {
					// re-check
					if (dependent.contains(datafile)) {
						checkResource(datafile);
					}
				}
				
				return;				
			}
			
			// removed res had schema? remove from mapping
			List<IFile> datafileList = datafileBySchema.get(schemaFile);
			if (!datafileList.contains(resource)) {
				datafileList.remove(resource);
				return;
			}
		}
	}

}
