package qupath.ext.analysisqueue.workflow;

import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.Projects;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import javax.imageio.ImageIO;

final class NativeFixture implements AutoCloseable {
    final Project<BufferedImage> project;
    final ProjectImageEntry<BufferedImage> entry;
    final ImageData<BufferedImage> data;
    final PathObject region;
    NativeFixture(Path folder, int tissueWidth) throws Exception {
        Class.forName("qupath.lib.scripting.QP"); // Register the same native JSON adapters as application startup.
        Files.createDirectories(folder);
        var image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 100; y++) for (int x = 0; x < 200; x++) image.setRGB(x, y, x < tissueWidth ? 0x966E46 : 0xFAFAFA);
        var path = folder.resolve("slide.png"); ImageIO.write(image, "PNG", path.toFile());
        project = Projects.createProject(folder.toFile(), BufferedImage.class);
        try (var server = ImageServers.buildServer(path.toUri())) {
            server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata()).pixelSizeMicrons(0.5, 0.5).build());
            entry = project.addImage(server.getBuilder()); entry.setImageName("Synthetic slide");
        }
        data = entry.readImageData(); data.setImageType(ImageData.ImageType.BRIGHTFIELD_H_DAB);
        region = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 200, 100, ImagePlane.getDefaultPlane()));
        region.setName("Outer region"); data.getHierarchy().addObject(region);
        entry.saveImageData(data); project.syncChanges();
        var classifier = folder.resolve("classifiers/pixel_classifiers/" + Synj1Analysis.CLASSIFIER + ".json");
        Files.createDirectories(classifier.getParent());
        try (var stream = getClass().getResourceAsStream("/threshold220.json")) { Files.copy(stream, classifier); }
    }
    List<String> ids() { return List.of(region.getID().toString()); }
    Path classifier() { return project.getPath().getParent().resolve("classifiers/pixel_classifiers/" + Synj1Analysis.CLASSIFIER + ".json"); }
    @Override public void close() throws Exception { data.close(); }
}
