// QuPath 0.7: run on the current project image from Automate > Project scripts.
// Measures every existing annotation classified exactly "tissue", saves, and exports.
import com.google.gson.GsonBuilder
import javafx.application.Platform
import qupath.lib.algorithms.IntensityFeaturesPlugin
import qupath.lib.gui.QuPathGUI
import qupath.lib.images.ImageData
import qupath.lib.io.PathIO
import qupath.lib.plugins.TaskRunnerUtils
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

if (Platform.isFxApplicationThread())
    throw new IllegalStateException('Use the script editor Run command so measurement runs in the background.')

def fx = { Closure operation ->
    def result = new CompletableFuture()
    Platform.runLater { try { result.complete(operation()) } catch (Throwable error) { result.completeExceptionally(error) } }
    result.get(60, TimeUnit.SECONDS)
}
def require = { boolean condition, String message ->
    if (!condition) throw new IllegalStateException(message)
}
def gui = QuPathGUI.getInstance()
def context = fx { [project:gui.getProject(), data:gui.getImageData()] }
def project = context.project
def data = context.data
require(project != null && data != null, 'Open a project image first.')
def entry = project.getEntry(data)
require(entry != null, 'The current image must belong to the project.')
require(data.getImageType() == ImageData.ImageType.BRIGHTFIELD_H_DAB, 'This command requires a Brightfield H-DAB image.')
def calibration = data.getServer().getPixelCalibration()
require(calibration.hasPixelSizeMicrons(), 'The image needs a pixel calibration in micrometres.')
def stains = data.getColorDeconvolutionStains()
require(stains != null && stains.isH_DAB(), 'The stain configuration must be Hematoxylin and DAB.')

def hierarchy = data.getHierarchy()
def before = fx {
    hierarchy.getAnnotationObjects().collectEntries { annotation ->
        [(annotation.getID()):[object:annotation, roi:annotation.getROI(), parent:annotation.getParent(),
            name:annotation.getName(), classification:annotation.getPathClass(), locked:annotation.isLocked(),
            measurements:new LinkedHashMap(annotation.getMeasurementList().asMap())]]
    }
}
def targets = before.values().findAll { it.classification?.toString() == 'tissue' }.collect { it.object }
require(!targets.isEmpty(), 'No tissue annotations found. Create/review the tissue regions first.')
require(targets.every { it.getROI()?.isArea() && it.getROI().getArea() > 0 }, 'Each tissue annotation must have a non-empty area.')
def selected = fx { new ArrayList(hierarchy.getSelectionModel().getSelectedObjects()) }
def primary = fx { hierarchy.getSelectionModel().getSelectedObject() }

final double pixelSizeMicrons = 0.5d
def prefix = String.format('ROI: %.2f %s per pixel: DAB: ', pixelSizeMicrons, '\u00b5m')
def meanKey = prefix + 'Mean'
def stdKey = prefix + 'Std.dev.'
def parameters = [pixelSizeMicrons:pixelSizeMicrons, region:'ROI', tileSizeMicrons:25.0,
    colorOD:false, colorStain1:false, colorStain2:true, colorStain3:false,
    colorRed:false, colorGreen:false, colorBlue:false, colorHue:false, colorSaturation:false, colorBrightness:false,
    doMean:true, doStdDev:true, doMinMax:false, doMedian:false, doHaralick:false, haralickDistance:1, haralickBins:32]
def gson = new GsonBuilder().setPrettyPrinting().create()
def label = LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyyMMdd-HHmmss')) + '-entry-' + entry.getID() + '-' + UUID.randomUUID().toString().substring(0,8)
def folder = project.getPath().getParent().resolve('Results').resolve('DAB 0.5 um').resolve(label)
Files.createDirectories(folder)
def report = [state:'RUNNING', started:Instant.now().toString(), image:entry.getImageName(), entryId:entry.getID(),
    method:'QuPath native IntensityFeaturesPlugin; existing tissue annotations', parameters:parameters,
    measurementColumns:[meanKey,stdKey], outputDirectory:folder.toString()]
def writeReport = { Files.writeString(folder.resolve('run.json'), gson.toJson(report)) }
writeReport()
long started = System.nanoTime()
boolean success = false
try {
    fx {
        require(gui.getImageData().is(data), 'The active image changed before measurement started.')
        hierarchy.getSelectionModel().setSelectedObjects(targets, targets[0])
    }
    println "Measuring ${targets.size()} tissue region(s) on ${entry.getImageName()} at 0.5 micrometres per pixel."
    def plugin = new IntensityFeaturesPlugin()
    def runner = TaskRunnerUtils.getDefaultInstance().createHeadlessTaskRunner(2)
    require(plugin.runPlugin(runner, data, gson.toJson(parameters)), 'Measurement did not complete; the image was not saved by this command.')
    require(!runner.isCancelled(), 'Measurement was cancelled; the image was not saved by this command.')
    report.measurementSeconds = (System.nanoTime()-started)/1e9
    targets.each { annotation ->
        require(Double.isFinite(annotation.getMeasurementList().get(meanKey)), 'Missing or invalid 0.5 micrometre DAB mean.')
        require(Double.isFinite(annotation.getMeasurementList().get(stdKey)), 'Missing or invalid 0.5 micrometre DAB standard deviation.')
    }
    fx {
        require(gui.getImageData().is(data) && gui.getProject().is(project), 'The active image/project changed. Results remain in memory; review before saving.')
        require(data.getColorDeconvolutionStains().is(stains) && data.getServer().getPixelCalibration() == calibration,
            'Stains or calibration changed during measurement. Review and rerun.')
        require(hierarchy.getAnnotationObjects()*.getID().toSet() == before.keySet(), 'Annotations changed during measurement. Review and rerun.')
        before.each { id, old ->
            def annotation = old.object
            require(annotation.getROI().is(old.roi) && annotation.getParent().is(old.parent) &&
                annotation.getName() == old.name && annotation.getPathClass() == old.classification && annotation.isLocked() == old.locked,
                'Annotation geometry/properties changed during measurement. Review and rerun.')
            old.measurements.each { key, value ->
                if (!(annotation in targets && key in [meanKey,stdKey]))
                    require(Objects.equals(annotation.getMeasurementList().asMap()[key], value), 'An unrelated measurement changed.')
            }
        }
        entry.saveImageData(data)
        project.syncChanges()
    }
    report.savedPath = data.getLastSavedPath()
    report.state = 'SAVED_VERIFYING'
    writeReport()
    // Read the actual saved data with the existing image server; do not close that shared server.
    def saved = PathIO.readImageData(Path.of(data.getLastSavedPath()), data.getServer())
    def savedAnnotations = saved.getHierarchy().getAnnotationObjects().collectEntries { [(it.getID()):it] }
    require(savedAnnotations.keySet() == before.keySet(), 'Saved annotation IDs differ from the input.')
    before.each { id, old ->
        def restored = savedAnnotations[id]
        require(restored.getROI() == old.roi && restored.getName() == old.name && restored.getPathClass() == old.classification &&
            restored.isLocked() == old.locked && restored.getParent()?.getID() == old.parent?.getID(), 'Saved annotation geometry/properties differ.')
        require(restored.getMeasurementList().asMap() == old.object.getMeasurementList().asMap(), 'Saved measurements differ from memory.')
    }
    def rows = targets.collect { annotation ->
        def restored = savedAnnotations[annotation.getID()]
        [image:entry.getImageName(), annotationId:restored.getID().toString(), annotation:restored.getName(), classification:restored.getPathClass().toString(),
            pixelSizeMicrons:pixelSizeMicrons, areaSquareMicrons:restored.getROI().getArea()*calibration.getPixelWidthMicrons()*calibration.getPixelHeightMicrons(),
            dabMean:restored.getMeasurementList().get(meanKey), dabStandardDeviation:restored.getMeasurementList().get(stdKey)]
    }
    def csvCell = { value -> '"' + Objects.toString(value, '').replace('"', '""') + '"' }
    def headings = ['Image','Annotation ID','Annotation','Classification','Pixel size (um)','Area (um^2)','DAB Mean','DAB Std.dev.']
    def lines = [headings.collect(csvCell).join(',')] + rows.collect { row -> row.values().collect(csvCell).join(',') }
    def csv = folder.resolve('measurements.csv')
    Files.writeString(csv, '\uFEFF' + lines.join('\r\n') + '\r\n')
    report.rows = rows
    report.csv = csv.toString()
    report.savedReadbackVerified = true
    report.state = 'COMPLETED'
    report.finished = Instant.now().toString()
    report.totalSeconds = (System.nanoTime()-started)/1e9
    writeReport()
    success = true
    println "Saved and verified ${entry.getImageName()}. CSV: ${csv}"
} catch (Throwable error) {
    report.state = 'FAILED'
    report.error = error.toString()
    writeReport()
    throw error
} finally {
    fx {
        if (gui.getImageData().is(data)) {
            if (success) hierarchy.getSelectionModel().setSelectedObjects(targets, targets[0])
            else hierarchy.getSelectionModel().setSelectedObjects(selected, primary)
        }
    }
}
