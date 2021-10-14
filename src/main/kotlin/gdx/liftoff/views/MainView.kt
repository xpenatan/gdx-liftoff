

package gdx.liftoff.views

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Net
import com.badlogic.gdx.Version
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.scenes.scene2d.Action
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.utils.Align
import com.github.czyzby.autumn.annotation.Destroy
import com.github.czyzby.autumn.annotation.Inject
import com.github.czyzby.autumn.mvc.component.ui.InterfaceService
import com.github.czyzby.autumn.mvc.config.AutumnActionPriority
import com.github.czyzby.autumn.mvc.stereotype.View
import com.github.czyzby.lml.annotation.LmlAction
import com.github.czyzby.lml.annotation.LmlActor
import com.github.czyzby.lml.annotation.LmlAfter
import com.github.czyzby.lml.annotation.LmlInject
import com.github.czyzby.lml.parser.LmlParser
import com.github.czyzby.lml.parser.action.ActionContainer
import com.github.czyzby.lml.vis.ui.VisFormTable
import com.kotcrab.vis.ui.util.ToastManager
import com.kotcrab.vis.ui.widget.file.FileChooser
import com.kotcrab.vis.ui.widget.file.FileChooserAdapter
import com.kotcrab.vis.ui.widget.tabbedpane.TabbedPane
import com.kotcrab.vis.ui.widget.toast.ToastTable
import gdx.liftoff.config.Configuration
import gdx.liftoff.config.inject
import gdx.liftoff.data.platforms.Android
import gdx.liftoff.data.project.Project
import gdx.liftoff.preferences.SdkVersionPreference
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryUtil.memAllocPointer
import org.lwjgl.system.MemoryUtil.memFree
import org.lwjgl.util.nfd.NativeFileDialog
import kotlin.Array
import com.badlogic.gdx.utils.Array as GdxArray

/**
 * Main application's view. Displays application's menu.
 */
@View(id = "main", value = "templates/main.lml", first = true)
@Suppress("unused") // Methods and fields accessed via reflection.
class MainView : ActionContainer {
    val toastManager: Lazy<ToastManager> = lazy {
        val manager = ToastManager(form.stage)
        manager.screenPadding = 40
        manager.alignment = Align.bottomRight
        manager
    }
    @Inject private val interfaceService: InterfaceService = inject()
    @LmlInject private val basicData: BasicProjectData = inject()
    @LmlInject private val advancedData: AdvancedProjectData = inject()
    @LmlInject @Inject private val platformsData: PlatformsData = inject()
    @LmlInject @Inject private val languagesData: LanguagesData = inject()
    @LmlInject @Inject private val extensionsData: ExtensionsData = inject()
    @LmlInject @Inject private val templatesData: TemplatesData = inject()
    @LmlActor("form") private val form: VisFormTable = inject()
    @LmlActor("notLatestVersion") private val notUpToDateToast: ToastTable = inject()

    @LmlAction("chooseDirectory")
    fun chooseDirectory() {
        pickDirectory(
            getDestination(),
            object : FileChooserAdapter() {
                override fun selected(files: GdxArray<FileHandle>?) {
                    val file = files?.first()
                    if (file != null) {
                        basicData.setDestination(file.path())
                    }
                }
            }
        )
    }

    @LmlAction("chooseSdkDirectory")
    fun chooseSdkDirectory() {
        pickDirectory(
            getAndroidSdkVersion(),
            object : FileChooserAdapter() {
                override fun selected(files: GdxArray<FileHandle>?) {
                    val file = files?.first()
                    if (file != null) {
                        basicData.setAndroidSdkPath(file.path())
                    }
                }
            }
        )
    }

    private fun pickDirectory(initialFolder: FileHandle, callback: FileChooserAdapter) {
        var initialPath = initialFolder.path()

        if (System.getProperty("os.name").lowercase().contains("win")) {
            initialPath = initialPath.replace("/", "\\")
        }

        val pathPointer = memAllocPointer(1)

        try {
            val status = NativeFileDialog.NFD_PickFolder(initialPath, pathPointer)

            if (status == NativeFileDialog.NFD_CANCEL) {
                callback.canceled()
                return
            }

            // unexpected error -> show visui dialog
            if (status != NativeFileDialog.NFD_OKAY) {
                throw Throwable("Native file dialog errored")
            }

            val folder = pathPointer.getStringUTF8(0)
            NativeFileDialog.nNFD_Free(pathPointer.get(0))

            val array = GdxArray<FileHandle>()
            array.add(Gdx.files.absolute(folder))

            callback.selected(array)
        } catch (e: Throwable) {
            val fileChooser = FileChooser(FileChooser.Mode.OPEN)
            fileChooser.selectionMode = FileChooser.SelectionMode.DIRECTORIES
            fileChooser.setDirectory(initialPath)
            fileChooser.setListener(callback)

            form.stage.addActor(fileChooser.fadeIn())
        } finally {
            memFree(pathPointer)
        }
    }

    @LmlAction("togglePlatform")
    fun togglePlatform(button: Button) {
        if (button.name == Android.ID) {
            platformsData.toggleAndroidPlatform(button.isChecked)
            revalidateForm()
        }
    }

    @LmlAction("mkdirs")
    fun createDestinationDirectory() {
        basicData.destination.mkdirs()
        revalidateForm()
    }

    @LmlAction("checkProjectDir")
    fun checkProjectDirectory() {
        basicData.revalidateDirectoryUtilityButtons()
    }

    @LmlAction("reloadSdkButtons")
    fun reloadAndroidSdkButtons() {
        basicData.revalidateSdkUtilityButtons()
    }

    @LmlAction("useLatestSdk")
    fun extractLatestAndroidApiVersions() {
        advancedData.androidSdkVersion = basicData.getLatestAndroidApiVersion().toString()
    }

    @LmlAction("useOldestSdk")
    fun extractOldestAndroidApiVersions() {
        advancedData.androidSdkVersion = basicData.getOldestAndroidApiVersion().toString()
    }

    @LmlAfter fun initiateVersions(parser: LmlParser) {
        languagesData.assignVersions(parser)
    }

    @LmlAfter fun checkSetupVersion() {
        // When using snapshots, we don't care if the version matches latest stable.
        if (Configuration.VERSION.endsWith("SNAPSHOT")) return

        val request = Net.HttpRequest(Net.HttpMethods.GET)
        request.url = "https://raw.githubusercontent.com/tommyettinger/gdx-liftoff/master/version.txt"
        val listener = object : Net.HttpResponseListener {
            override fun handleHttpResponse(httpResponse: Net.HttpResponse) {
                val latestStable = httpResponse.resultAsString.trim()
                if (Configuration.VERSION != latestStable) {
                    Gdx.app.postRunnable { toastManager.value.show(notUpToDateToast) }
                }
            }

            override fun cancelled() {
                // Never cancelled.
            }

            override fun failed(t: Throwable?) {
                // Ignored. The user might not be connected.
            }
        }
        Gdx.net.sendHttpRequest(request, listener)
    }

    fun revalidateForm() {
        form.formValidator.validate()
        basicData.revalidateDirectoryUtilityButtons()
        basicData.revalidateSdkUtilityButtons()
    }

    @LmlAction("platforms") fun getPlatforms(): Iterable<*> = platformsData.platforms.keys.sorted()
    @LmlAction("show") fun getTabShowingAction(): Action = Actions.sequence(Actions.alpha(0f), Actions.fadeIn(0.1f))
    @LmlAction("hide") fun getTabHidingAction(): Action = Actions.fadeOut(0.1f)
    @LmlAction("gdxVersion") fun getGdxVersion(): String = Version.VERSION
    @LmlAction("gwtVersions") fun getGwtVersions(): Array<String> = arrayOf("2.8.2")
    @LmlAction("jvmLanguages") fun getLanguages(): Array<String> = languagesData.languages
    @LmlAction("jvmLanguagesVersions") fun getLanguagesVersions(): Array<String> = languagesData.versions
    @LmlAction("templates") fun getOfficialTemplates(): Array<String> =
        templatesData.officialTemplates.map { it.id }.sortedWith { left, right -> left.compareTo(right) }
            .toTypedArray()

    @LmlAction("thirdPartyTemplates") fun getThirdPartyTemplates(): Array<String> =
        templatesData.thirdPartyTemplates.map { it.id }.sorted().toTypedArray()

    @LmlAction("officialExtensions") fun getOfficialExtensions(): Array<String> =
        extensionsData.official.map { it.id }.sorted().toTypedArray()

    @LmlAction("officialExtensionsUrls") fun getOfficialExtensionsUrls(): Array<String> =
        extensionsData.official.sortedBy { it.id }.map { it.url }.toTypedArray()

    @LmlAction("thirdPartyExtensions") fun getThirdPartyExtensions(): Array<String> =
        extensionsData.thirdParty.map { it.id }.sorted().toTypedArray()

    @LmlAction("thirdPartyExtensionsUrls") fun getThirdPartyExtensionsUrls(): Array<String> =
        extensionsData.thirdParty.sortedBy { it.id }.map { it.url }.toTypedArray()

    @LmlAction("initTabs") fun initiateTabbedPane(tabbedPane: TabbedPane.TabbedPaneTable) {
        tabbedPane.tabbedPane.tabsPane.horizontalFlowGroup.spacing = 2f
    }

    fun getDestination(): FileHandle = basicData.destination
    fun getAndroidSdkVersion(): FileHandle = basicData.androidSdk

    fun createProject(): Project = Project(
        basic = basicData,
        platforms = platformsData.getSelectedPlatforms(),
        advanced = advancedData,
        languages = languagesData,
        extensions = extensionsData,
        template = templatesData.getSelectedTemplate()
    )

    @LmlAction("minimize") fun iconify() = GLFW.glfwIconifyWindow(GLFW.glfwGetCurrentContext())

    @LmlAction("initTitleTable")
    fun addWindowDragListener(actor: Actor) {
        actor.addListener(object : InputListener() {
            private val context = GLFW.glfwGetCurrentContext()
            private var startX = 0
            private var startY = 0
            private var offsetX = 0
            private var offsetY = 0
            private val cursorX = BufferUtils.createDoubleBuffer(1)
            private val cursorY = BufferUtils.createDoubleBuffer(1)
            private val windowX = BufferUtils.createIntBuffer(1)
            private val windowY = BufferUtils.createIntBuffer(1)

            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                GLFW.glfwGetCursorPos(context, cursorX, cursorY)
                startX = getX()
                startY = getY()
                return true
            }

            override fun touchDragged(event: InputEvent?, x: Float, y: Float, pointer: Int) {
                GLFW.glfwGetCursorPos(context, cursorX, cursorY)
                offsetX = getX() - startX
                offsetY = getY() - startY
                GLFW.glfwGetWindowPos(context, windowX, windowY)
                GLFW.glfwSetWindowPos(context, windowX.get(0) + offsetX, windowY.get(0) + offsetY)
            }

            private fun getX(): Int = MathUtils.floor(cursorX.get(0).toFloat())
            private fun getY(): Int = MathUtils.floor(cursorY.get(0).toFloat())
        })
    }

    /**
     * Explicitly forces saving of Android SDK versions. They might not be properly updated as change events are not
     * fired on programmatic SDK and tools versions changes.
     */
    @Destroy(priority = AutumnActionPriority.TOP_PRIORITY)
    fun saveAndroidSdkVersions(api: SdkVersionPreference) {
        api.set(advancedData.androidSdkVersion)
    }
}