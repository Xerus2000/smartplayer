package xerus.music

import com.gluonhq.charm.down.Services
import com.gluonhq.charm.down.plugins.StorageService
import javafx.application.Application
import javafx.application.Platform
import javafx.css.ParsedValue
import javafx.css.StyleConverter
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Labeled
import javafx.scene.control.Tooltip
import javafx.scene.image.Image
import javafx.scene.input.KeyEvent
import javafx.scene.paint.Color
import javafx.stage.Stage
import xerus.ktutil.*
import xerus.ktutil.helpers.Parser
import xerus.ktutil.helpers.ParserException
import xerus.ktutil.javafx.StylingTools
import xerus.ktutil.javafx.applySkin
import xerus.ktutil.javafx.checkFx
import xerus.ktutil.javafx.properties.*
import xerus.music.library.Library
import xerus.music.library.brightness
import xerus.music.player.Player
import xerus.music.player.Player.curSong
import xerus.music.view.SongViewer
import java.io.File
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Level

val platform = System.getProperty("javafx.platform", null)?.toLowerCase() ?: "desktop"
val isDesktop = platform == "desktop"

typealias logger = XerusLogger

lateinit var stage: Stage
lateinit var root: Parent

lateinit var executor: ExecutorService

fun main(args: Array<String>) {
	Application.launch(Launcher::class.java, *args)
}

const val TITLE = "Cognita Player"

class Launcher : Application() {
	
	override fun init() {
		val level: String
		val args = parameters.raw
		if (args.size > 0) {
			val arg = args[0].toUpperCase()
			level = arg
			if (args.size > 1 && args[1].toLowerCase() == "save")
				Settings.LOGLEVEL.put(arg)
		} else {
			level = Settings.LOGLEVEL.get()
		}
		XerusLogger(level)
		if (isDesktop)
			logger.logLines(Level.CONFIG,
					"This application can be launched from console using \"java -jar %jarname%.jar %LogLevel%\" (wrapped in % signs are placeholders that should be replaced by their appropriate value)",
					"LogLevel can be one of: OFF, SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST. The additional argument \"save\" will result in the given LogLevel becoming the new default.",
					"The default LogLevel is currently " + Settings.LOGLEVEL.get())
		
		//XerusLogger.parseArgs(*parameters.raw.toTypedArray())
		logger.config("Starting player - Platform: $platform")
		executor = Executors.newFixedThreadPool(1)
		if (!Settings.LIBRARY.get().isEmpty()) {
			val lib = File(Settings.LIBRARY.get())
			if (lib.exists() && lib.canRead())
				Library.initialize(lib.toString())
		}
	}
	
	override fun start(s: Stage) {
		stage = s
		stage.title = TITLE
		stage.icons.add(Image("css/satin/music.png"))
		if (!isDesktop)
			stage.isFullScreen = true
		
		Thread.setDefaultUncaughtExceptionHandler { t, e -> logger.severe(String.format("Uncaught exception in %s: %s", t, e.getStackTraceString())) }
		
		val fxml = Settings.FXML.get()
		loadFXML(if (fxml.isEmpty()) null else fxml)
		if (stage.scene == null) {
			logger.severe("No Scene initialized. Aborting!")
			return
		}
		logger.config("Showing stage")
		stage.show()
		
		if (!Library.inited)
			Platform.runLater {
				logger.config("Issuing Library select dialog because no valid Library is defined yet")
				musicPlayer.selectLibrary(Services.get(StorageService::class.java).flatMap { ss -> ss.getPublicStorage("") }.map<String>({ it.toString() }).orElse(null))
			}
		
		if (isDesktop)
			stage.titleProperty().dependOn(curSong) {
				var title = TITLE
				if (it != null)
					title += " - $it"
				title
			}
		
		Natives.instance.init()
	}
	
	override fun stop() {
		musicPlayer.quit()
	}
	
	companion object {
		
		fun loadFXML(location: String?) {
			try {
				val url = if (location == null) getResource("layouts/$platform.fxml") else File(location).toURI().toURL()
				root = FXMLLoader.load(url)
			} catch (e: Exception) {
				logger.severe("Couldn't load FXML: " + e.toString())
				logger.warning(e.getStackTraceString())
				if (location != null)
					loadFXML(null)
				return
			}
			
			// Library Viewers
			val views = StylingTools.findByClass(root, SongViewer::class.java).toTypedArray()
			Library.addViews(*views)
			musicPlayer.searchField?.let {
				for (view in views)
					view.bindFilter(it)
			}
			
			// Placeholders
			@Suppress("unchecked_cast")
			val labels = StylingTools.find(root) { it is Labeled && it.text.contains("%") } as Collection<Labeled>
			if (labels.isNotEmpty()) {
				val parser = Parser('%')
				val available = mapOf(Pair("curSong", Player.curSong), Pair("nextSong", Player.nextSong),
						Pair("playedTime", Player.player.playedMillis.dependentObservable { formatTimeDynamic(it.toLong()) }),
						Pair("totalTime", Player.player.totalMillis.dependentObservable { formatTimeDynamic(it.toLong(), Player.player.totalMillis.get().toLong()) }),
						Pair("clock", TimedObservable(1000) { formattedTime() }))
				for (label in labels) {
					val placeholders = available.filter { label.text.contains(it.key) }
					if (placeholders.isEmpty())
						continue
					try {
						val matcher = parser.createMatcher(label.text,
								*placeholders.mapTo(ArrayList(placeholders.size)) { it.key }.toTypedArray())
						val properties =
								placeholders.mapTo(ArrayList(placeholders.size)) { it.value }.toTypedArray()
						checkFx {
							label.textProperty().bindSoft({
								matcher.apply(*properties.map { it.value?.toString() ?: " - " }.toTypedArray())
										.also { label.tooltip = Tooltip(it) }
							}, *properties)
						}
					} catch (e: ParserException) {
						logger.warning(e.match + " is not a valid placeholder in Labeled " + label)
					}
				}
			}
			
			// Scene
			val scene = Scene(root)
			stage.scene = scene
			stage.minWidth = root.minWidth(-1.0)
			stage.minHeight = root.minHeight(-1.0)
			applyCSS()
			if (!isDesktop)
				scene.addEventFilter(KeyEvent.KEY_PRESSED) { Player.handleVolumeButtons(it) }
		}
		
		// region CSS Color hacking
		
		fun applyCSS() {
			val file = stage.scene.applySkin(Settings.CSS.get())
			try {
				val sheet = invokeCSSMethod(if (isJava8) "parser.CSSParser" else "CssParser", "parse", null, URL(file))
				val rules = invokeCSSMethod("Stylesheet", "getRules", sheet) as List<*>
				outer@ for (rule in rules) {
					val declarations = invokeCSSMethod("Rule", "getDeclarations", rule) as List<*>
					for (declaration in declarations) {
						val prop = invokeCSSMethod("Declaration", "getProperty", declaration)
						if (prop == "-fx-base") {
							@Suppress("UNCHECKED_CAST")
							val parsedValue = invokeCSSMethod("Declaration", "getParsedValue", declaration) as ParsedValue<String, Color>
							brightness = (StyleConverter.getColorConverter().convert(parsedValue, null).brightness - 0.7) / 3 + 0.7
							logger.config("Song rating color brightness set to $brightness")
							break@outer
						}
					}
				}
			} catch (t: Throwable) {
				logger.throwing("Launcher", "applyCSS", t)
			}
			
		}
		
		private val isJava8 = System.getProperty("java.specification.version").startsWith("1.")
		private val cssPackage = (if (isJava8) "com.sun." else "") + "javafx.css."
		private fun invokeCSSMethod(classname: String, methodName: String, target: Any?, vararg params: Any): Any {
			val clazz = Class.forName(cssPackage + classname)
			val paramClasses = params.map { it.javaClass }.toTypedArray()
			return clazz.getMethod(methodName, *paramClasses).invoke(target ?: clazz.newInstance(), *params)
		}
		
		// endregion
		
		/**
		 * displays important errors to the user
		 */
		fun showError(cause: String, t: Throwable) {
			logger.throwing(null, cause, t)
		}
	}
	
}
