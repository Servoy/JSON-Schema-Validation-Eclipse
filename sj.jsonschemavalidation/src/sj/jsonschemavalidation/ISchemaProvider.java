package sj.jsonschemavalidation;

import org.eclipse.core.resources.IFile;

public interface ISchemaProvider {
	static final String EXTENSION_ID = "sj.jsonschemavalidation.schemaprovider";
	
	String getSchemaFor(IFile file);
	
}
