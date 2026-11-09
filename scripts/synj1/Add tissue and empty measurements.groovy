// Add permanent classifier fractions and export all annotations, preserving existing DAB values.
import qupath.lib.gui.QuPathGUI

def project = QuPathGUI.getInstance().getProject()
if (project == null) throw new IllegalStateException('Open a project image first.')
def workflow = project.getPath().getParent().resolve('scripts/Measure tissue DAB at 0.5 um.groovy').toFile()
new GroovyShell(getClass().getClassLoader(), new Binding([measureDab:false])).evaluate(workflow)
