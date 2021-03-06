package net.sourceforge.plantuml.eclipse.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.eclipse.Activator;

public class PlantumlUtil {

	public static final String PLANTUML_MARKER = "plantumlmarker";
	public static final String ORIGINAL_PATH_ATTRIBUTE = "original";
	public static final String DIAGRAM_SOURCE_ATTRIBUTE = "diagramSource";
	public static final String TARGET_PATH_ATTRIBUTE = "target";

	public static void updateMarker(final IFile file, final String textDiagram, IPath target, final boolean create, final Map<String, Object> markerAttributes) {
		final IMarker marker = getPlantUmlMarker(file, create);
		if (marker != null) {
			if (target == null) {
				try {
					final Object targetAttribute = marker.getAttribute(TARGET_PATH_ATTRIBUTE);
					if (targetAttribute instanceof String) {
						target = new Path(String.valueOf(targetAttribute));
					}
				} catch (final CoreException e) {
				}
			}
			final Map<String, Object> attributes = new HashMap<String, Object>();
			if (markerAttributes != null) {
				attributes.putAll(markerAttributes);
			}
			attributes.put(ORIGINAL_PATH_ATTRIBUTE, file.getFullPath().toString());
			attributes.put(DIAGRAM_SOURCE_ATTRIBUTE, textDiagram);
			attributes.put(TARGET_PATH_ATTRIBUTE, (target != null ? target.toString() : null));
			try {
				//				System.out.println("Updating marker for " + file.getFullPath() + ": " + attributes);
				marker.setAttributes(attributes);
			} catch (final CoreException e) {
			}
		}
	}

	public static IMarker getPlantUmlMarker(final IFile file, final boolean create) {
		IMarker marker = null;
		try {
			final IMarker[] markers = file.findMarkers(PLANTUML_MARKER, false, IResource.DEPTH_ZERO);
			if (markers != null && markers.length == 1) {
				marker = markers[0];
			} else if (create) {
				marker = file.createMarker(PLANTUML_MARKER);
			}
		} catch (final CoreException e1) {
		}
		return marker;
	}

	public static IResourceChangeListener createResourceListener() {
		return new AutoSaveHelper();
	}

	private static class AutoSaveHelper implements IResourceChangeListener, IResourceDeltaVisitor {

		@Override
		public void resourceChanged(final IResourceChangeEvent changeEvent) {
			try {
				changeEvent.getDelta().accept(this);
			} catch (final CoreException e) {
			}
		}

		private Diagram diagram;

		@Override
		public boolean visit(final IResourceDelta delta) throws CoreException {
			if (delta.getKind() != IResourceDelta.CHANGED || (delta.getFlags() & IResourceDelta.CONTENT) == 0) {
				return true;
			}
			final IResource resource = delta.getResource();
			if (resource instanceof IFile) {
				final IMarker marker = getPlantUmlMarker((IFile) resource, false);
				if (marker != null) {
					final Object target = marker.getAttribute(TARGET_PATH_ATTRIBUTE);
					if (target != null) {
						final IPath path = resource.getFullPath();
						//						System.out.println("Updating image for " + path + " @ " + target);
						for (final DiagramTextProvider diagramTextProvider : Activator.getDefault().getDiagramTextProviders(null)) {
							if (diagramTextProvider instanceof DiagramTextProvider2) {
								final DiagramTextProvider2 diagramTextProvider2 = (DiagramTextProvider2) diagramTextProvider;
								if (diagramTextProvider2.supportsPath(path)) {
									final String textDiagram = diagramTextProvider2.getDiagramText(path);
									//									System.out.println("Diagram for " + path + ": " + textDiagram);
									if (textDiagram != null) {
										if (diagram == null) {
											diagram = new Diagram();
										}
										diagram.setTextDiagram(textDiagram);
										try {
											final ImageData image = diagram.getImage(path, 0, null);
											saveDiagramImage(path, textDiagram, image, new Path(target.toString()), false);
										} catch (final Exception e) {
											System.err.println(e);
										}
										break;
									}
								}
							}
						}
					}
				}
			}
			return false;
		}
	}

	public static void saveDiagramImage(final IPath sourcePath, final String textDiagram, final ImageData image, final IPath targetPath, final boolean create) throws Exception {
		final IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(targetPath);
		if (file != null && (create || file.exists())) {
			final String ext = targetPath.getFileExtension();
			if ("svg".equals(ext)) {
				createSvgFile(file, textDiagram);
			} else if ("puml".equals(ext) || "plantuml".equals(ext)) {
				saveImage(file, textDiagram.getBytes());
			} else {
				createImageFile(file, ext, image);
			}
			if (sourcePath != null) {
				final IFile sourceFile = ResourcesPlugin.getWorkspace().getRoot().getFile(sourcePath);
				if (sourceFile != null && sourceFile.exists()) {
					updateMarker(sourceFile, textDiagram, targetPath, false, null);
				}
			}
		}
	}

	private static void createImageFile(final IFile file, final String format, final ImageData imageData) throws Exception {
		final ImageLoader loader = new ImageLoader();
		loader.data = new ImageData[]{imageData};
		final int swtFormat = SWT.class.getField("IMAGE_" + format.toUpperCase()).getInt(null);
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(32768);
		loader.save(outputStream, swtFormat);
		saveImage(file, outputStream.toByteArray());
	}

	private static void saveImage(final IFile file, final byte[] bytes) throws CoreException {
		final ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
		final NullProgressMonitor progressMonitor = new NullProgressMonitor();
		if (file.exists()) {
			file.setContents(inputStream, IResource.FORCE, progressMonitor);
		} else {
			file.create(inputStream, IResource.FORCE, progressMonitor);
		}
		file.setDerived(true, progressMonitor);
	}

	private static void createSvgFile(final IFile file, final String textDiagram) throws Exception {
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(32768);
		final SourceStringReader reader = new SourceStringReader(textDiagram);
		reader.outputImage(outputStream, new FileFormatOption(FileFormat.SVG));
		saveImage(file, outputStream.toByteArray());
	}
}
