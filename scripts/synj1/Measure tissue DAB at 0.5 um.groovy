// QuPath 0.7: run on the current project image from Automate > Project scripts.
// Measures DAB in tissue and permanent tissue/empty fractions in every area annotation.
import com.google.gson.GsonBuilder
import javafx.application.Platform
import qupath.lib.algorithms.IntensityFeaturesPlugin
import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.measure.ObservableMeasurementTableData
import qupath.lib.images.ImageData
import qupath.lib.io.PathIO
import qupath.lib.objects.PathObjects
import qupath.lib.plugins.TaskRunnerUtils
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep
import qupath.opencv.ml.pixel.PixelClassifierTools
import qupath.opencv.ml.pixel.PixelClassificationMeasurementManager
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

if (Platform.isFxApplicationThread())
    throw new IllegalStateException('Use the script editor Run command so measurement runs in the background.')
// The companion command skips DAB recalculation when adding missing fractions.
if (binding.hasVariable('measureDab') && !(binding.getVariable('measureDab') instanceof Boolean))
    throw new IllegalArgumentException('measureDab must be a boolean.')
final boolean measureDab = !binding.hasVariable('measureDab') || binding.getVariable('measureDab') == true
final String classifierName = 'threshhold 220 very high synj1'
final String classificationPrefix = 'Tissue/empty'
// This QuPath build rounds classifier values to float precision internally.
// Allow four float ULPs at 100% (about 0.000031 percentage points).
final double percentageTolerance = 4.0d * Math.ulp(100.0f)

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
def classifierFile = project.getPath().getParent().resolve('classifiers/pixel_classifiers/' + classifierName + '.json')
def classifierHash = MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(classifierFile)).encodeHex().toString()
def classifier = project.getPixelClassifiers().get(classifierName)
require(classifier != null, 'The saved tissue/empty classifier is missing.')
require(classifier.getMetadata().getClassificationLabels().values()*.toString().toSet() == ['tissue','empty'].toSet(),
    'The classifier must contain tissue and empty classes.')

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
def classificationTargets = before.values().findAll { it.roi?.isArea() && it.roi.getArea() > 0 }.collect { it.object }
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
def folder = project.getPath().getParent().resolve('Results').resolve('Complete annotation measurements').resolve(label)
Files.createDirectories(folder)
def report = [state:'RUNNING', started:Instant.now().toString(), image:entry.getImageName(), entryId:entry.getID(),
    method:'QuPath native DAB intensity and pixel-classifier measurements; existing annotations', parameters:parameters, dabRecomputed:measureDab,
    classifier:[name:classifierName,sha256:classifierHash,prefix:classificationPrefix,
        pixelSizeMicrons:classifier.getMetadata().getInputResolution().getAveragedPixelSizeMicrons()],
    measurementColumns:[meanKey,stdKey], outputDirectory:folder.toString()]
def writeReport = { Files.writeString(folder.resolve('run.json'), gson.toJson(report)) }
writeReport()
long started = System.nanoTime()
boolean success = false
try {
    if (measureDab) {
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
    }
    report.state = 'CLASSIFYING'
    report.classificationObjectsCompleted = 0
    writeReport()
    def staged = classificationTargets.collectEntries { annotation ->
        [(annotation.getID()):PathObjects.createAnnotationObject(before[annotation.getID()].roi, annotation.getPathClass())]
    }
    def classificationKeys
    def classifierServer = PixelClassifierTools.createPixelClassificationServer(data, classifier)
    long classificationStarted = System.nanoTime()
    try {
        // Submit the collection together so QuPath can parallelize complex ROIs.
        // With three annotations, this uses two object and two tile workers.
        def manager = new PixelClassificationMeasurementManager(classifierServer) {
            @Override protected int calculatePreferredParallelism() { return 4 }
        }
        classificationKeys = manager.getMeasurementNames().collect { classificationPrefix + ': ' + it }
        require(classificationKeys.size() == 4, 'Expected tissue/empty percentages and calibrated areas.')
        require(manager.addMeasurements(staged.values(), classificationPrefix), 'Pixel classification did not complete.')
        staged.each { id, temporary ->
            classificationKeys.each { key ->
                require(Double.isFinite(temporary.getMeasurementList().get(key)), 'Missing classifier measurement: ' + key)
            }
            def measurements = temporary.getMeasurementList()
            double tissuePercent = measurements.get(classificationPrefix + ': tissue %')
            double emptyPercent = measurements.get(classificationPrefix + ': empty %')
            double tissueArea = measurements.get(classificationPrefix + ': tissue area \u00b5m^2')
            double emptyArea = measurements.get(classificationPrefix + ': empty area \u00b5m^2')
            require(tissuePercent >= 0 && emptyPercent >= 0 &&
                Math.abs(tissuePercent + emptyPercent - 100.0) <= percentageTolerance,
                "Classifier percentages do not sum to 100% within native precision: ${tissuePercent} + ${emptyPercent}.")
            require(tissueArea >= 0 && emptyArea >= 0 && tissueArea + emptyArea > 0 &&
                Math.abs(tissuePercent - tissueArea / (tissueArea + emptyArea) * 100.0) <= percentageTolerance,
                'Classifier areas and percentages disagree beyond native precision.')
            report.classificationObjectsCompleted++
            writeReport()
        }
    } finally { classifierServer.close() }
    report.classificationSeconds = (System.nanoTime() - classificationStarted) / 1e9
    report.classificationColumns = classificationKeys
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
                if (!(measureDab && annotation in targets && key in [meanKey,stdKey]))
                    require(Objects.equals(annotation.getMeasurementList().asMap()[key], value), 'An unrelated measurement changed.')
            }
        }
        // Commit staged values only after every object has measured and input checks pass.
        classificationTargets.each { annotation ->
            classificationKeys.each { key -> annotation.getMeasurementList().put(key, staged[annotation.getID()].getMeasurementList().get(key)) }
            annotation.getMeasurementList().close()
        }
        hierarchy.fireObjectMeasurementsChangedEvent(classifier, classificationTargets)
        data.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep('Add permanent tissue/empty measurements',
            [classifier:classifierName,prefix:classificationPrefix,sha256:classifierHash,objects:classificationTargets.size()],
            'selectObjects(getAnnotationObjects().findAll { it.getROI()?.isArea() });\naddPixelClassifierMeasurements("' + classifierName + '", "' + classificationPrefix + '");'))
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
    def table = new ObservableMeasurementTableData()
    table.setImageData(saved, savedAnnotations.values())
    def headings = table.getAllNames().findAll { !it.startsWith('(Live)') }
    def rows = savedAnnotations.values().collect { annotation ->
        headings.collectEntries { key ->
            def value
            if (table.isNumericMeasurement(key)) {
                double number = table.getNumericValue(annotation, key)
                value = Double.isFinite(number) ? number : null
            } else value = table.getStringValue(annotation, key)
            [(key):value]
        }
    }
    def csvCell = { value -> '"' + Objects.toString(value, '').replace('"', '""') + '"' }
    def lines = [headings.collect(csvCell).join(',')] + rows.collect { row -> headings.collect { csvCell(row[it]) }.join(',') }
    def csv = folder.resolve('measurements.csv')
    Files.writeString(csv, '\uFEFF' + lines.join('\r\n') + '\r\n')
    report.rows = rows
    report.columns = headings
    report.geometryAndPreviousMeasurementsPreserved = true
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
return report
