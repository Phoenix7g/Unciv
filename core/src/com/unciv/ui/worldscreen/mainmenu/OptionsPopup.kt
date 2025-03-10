package com.unciv.ui.worldscreen.mainmenu

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Net
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.MainMenuScreen
import com.unciv.UncivGame
import com.unciv.logic.MapSaver
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.UncivSound
import com.unciv.models.metadata.checkMultiplayerServerWithPort
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.Ruleset.RulesetError
import com.unciv.models.ruleset.Ruleset.RulesetErrorSeverity
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.tilesets.TileSetCache
import com.unciv.models.translations.TranslationFileWriter
import com.unciv.models.translations.tr
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.civilopedia.FormattedLine
import com.unciv.ui.civilopedia.MarkupRenderer
import com.unciv.ui.newgamescreen.TranslatedSelectBox
import com.unciv.ui.utils.*
import com.unciv.ui.utils.LanguageTable.Companion.addLanguageTables
import com.unciv.ui.utils.UncivTooltip.Companion.addTooltip
import com.unciv.ui.worldscreen.WorldScreen
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.Charset
import java.util.*
import kotlin.math.floor
import com.badlogic.gdx.utils.Array as GdxArray

/**
 * The Options (Settings) Popup
 * @param previousScreen The caller - note if this is a [WorldScreen] or [MainMenuScreen] they will be rebuilt when major options change.
 */
//region Fields
class OptionsPopup(val previousScreen: BaseScreen) : Popup(previousScreen) {
    private val settings = previousScreen.game.settings
    private val tabs: TabbedPager
    private val resolutionArray = com.badlogic.gdx.utils.Array(arrayOf("750x500", "900x600", "1050x700", "1200x800", "1500x1000"))
    private var modCheckFirstRun = true   // marker for automatic first run on selecting the page
    private var modCheckBaseSelect: TranslatedSelectBox? = null
    private val modCheckResultTable = Table()
    private val selectBoxMinWidth: Float
    private val previousMaxWorldZoom = settings.maxWorldZoomOut

    //endregion

    companion object {
        private const val modCheckWithoutBase = "-none-"
    }

    init {
        settings.addCompletedTutorialTask("Open the options table")

        innerTable.pad(0f)
        val tabMaxWidth: Float
        val tabMinWidth: Float
        val tabMaxHeight: Float
        previousScreen.run {
            selectBoxMinWidth = if (stage.width < 600f) 200f else 240f
            tabMaxWidth = if (isPortrait()) stage.width - 10f else 0.8f * stage.width
            tabMinWidth = 0.6f * stage.width
            tabMaxHeight = (if (isPortrait()) 0.7f else 0.8f) * stage.height
        }
        tabs = TabbedPager(tabMinWidth, tabMaxWidth, 0f, tabMaxHeight,
            headerFontSize = 21, backgroundColor = Color.CLEAR, capacity = 8)
        add(tabs).pad(0f).grow().row()

        tabs.addPage("About", getAboutTab(), ImageGetter.getExternalImage("Icon.png"), 24f)
        tabs.addPage("Display", getDisplayTab(), ImageGetter.getImage("UnitPromotionIcons/Scouting"), 24f)
        tabs.addPage("Gameplay", getGamePlayTab(), ImageGetter.getImage("OtherIcons/Options"), 24f)
        tabs.addPage("Language", getLanguageTab(), ImageGetter.getImage("FlagIcons/${settings.language}"), 24f)
        tabs.addPage("Sound", getSoundTab(), ImageGetter.getImage("OtherIcons/Speaker"), 24f)
        tabs.addPage("Multiplayer", getMultiplayerTab(), ImageGetter.getImage("OtherIcons/Multiplayer"), 24f)
        tabs.addPage("Advanced", getAdvancedTab(), ImageGetter.getImage("OtherIcons/Settings"), 24f)
        if (RulesetCache.size > 1) {
            tabs.addPage("Locate mod errors", getModCheckTab(), ImageGetter.getImage("OtherIcons/Mods"), 24f) { _, _ ->
                if (modCheckFirstRun) runModChecker()
            }
        }
        if (Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT) && (Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT) || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT))) {
            tabs.addPage("Debug", getDebugTab(), ImageGetter.getImage("OtherIcons/SecretOptions"), 24f, secret = true)
        }

        addCloseButton {
            previousScreen.game.musicController.onChange(null)
            previousScreen.game.limitOrientationsHelper?.allowPortrait(settings.allowAndroidPortrait)
            if (previousScreen is WorldScreen)
                previousScreen.enableNextTurnButtonAfterOptions()
        }.padBottom(10f)

        pack() // Needed to show the background.
        center(previousScreen.stage)
    }

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (!visible) return
        tabs.askForPassword(secretHashCode = 2747985)
        if (tabs.activePage < 0) tabs.selectPage(2)
    }

    /** Reload this Popup after major changes (resolution, tileset, language) */
    private fun reloadWorldAndOptions() {
        settings.save()
        if (previousScreen is WorldScreen) {
            previousScreen.game.worldScreen = WorldScreen(previousScreen.gameInfo, previousScreen.viewingCiv)
            previousScreen.game.setWorldScreen()
        } else if (previousScreen is MainMenuScreen) {
            previousScreen.game.setScreen(MainMenuScreen())
        }
        (previousScreen.game.screen as BaseScreen).openOptionsPopup()
    }

    //region Page builders

    private fun getAboutTab(): Table {
        defaults().pad(5f)
        val version = previousScreen.game.version
        val versionAnchor = version.replace(".","")
        val lines = sequence {
            yield(FormattedLine(extraImage = "banner", imageSize = 240f, centered = true))
            yield(FormattedLine())
            yield(FormattedLine("{Version}: $version", link = "https://github.com/yairm210/Unciv/blob/master/changelog.md#$versionAnchor"))
            yield(FormattedLine("See online Readme", link = "https://github.com/yairm210/Unciv/blob/master/README.md#unciv---foss-civ-v-for-androiddesktop"))
            yield(FormattedLine("Visit repository", link = "https://github.com/yairm210/Unciv"))
        }
        return MarkupRenderer.render(lines.toList()).pad(20f)
    }

    private fun getLanguageTab() = Table(BaseScreen.skin).apply {
        val languageTables = this.addLanguageTables(tabs.prefWidth * 0.9f - 10f)

        var chosenLanguage = settings.language
        fun selectLanguage() {
            settings.language = chosenLanguage
            settings.updateLocaleFromLanguage()
            previousScreen.game.translations.tryReadTranslationForCurrentLanguage()
            reloadWorldAndOptions()
        }
        fun updateSelection() {
            languageTables.forEach { it.update(chosenLanguage) }
            if (chosenLanguage != settings.language)
                selectLanguage()
        }
        updateSelection()

        languageTables.forEach {
            it.onClick {
                chosenLanguage = it.language
                updateSelection()
            }
        }
    }

    private fun getDisplayTab() = Table(BaseScreen.skin).apply {
        pad(10f)
        defaults().pad(2.5f)

        addCheckbox("Show unit movement arrows", settings.showUnitMovements, true) { settings.showUnitMovements = it }
        addCheckbox("Show tile yields", settings.showTileYields, true) { settings.showTileYields = it } // JN
        addCheckbox("Show worked tiles", settings.showWorkedTiles, true) { settings.showWorkedTiles = it }
        addCheckbox("Show resources and improvements", settings.showResourcesAndImprovements, true) { settings.showResourcesAndImprovements = it }
        addCheckbox("Show tutorials", settings.showTutorials, true) { settings.showTutorials = it }
        addCheckbox("Show pixel units", settings.showPixelUnits, true) { settings.showPixelUnits = it }
        addCheckbox("Show pixel improvements", settings.showPixelImprovements, true) { settings.showPixelImprovements = it }

        addMinimapSizeSlider()

        addResolutionSelectBox()

        addTileSetSelectBox()

        addCheckbox("Continuous rendering", settings.continuousRendering) {
            settings.continuousRendering = it
            Gdx.graphics.isContinuousRendering = it
        }

        val continuousRenderingDescription = "When disabled, saves battery life but certain animations will be suspended"
        val continuousRenderingLabel = WrappableLabel(continuousRenderingDescription,
                tabs.prefWidth, Color.ORANGE.brighten(0.7f), 14)
        continuousRenderingLabel.wrap = true
        add(continuousRenderingLabel).colspan(2).padTop(10f).row()
    }

    private fun getGamePlayTab() = Table(BaseScreen.skin).apply {
        pad(10f)
        defaults().pad(5f)
        addCheckbox("Check for idle units", settings.checkForDueUnits, true) { settings.checkForDueUnits = it }
        addCheckbox("Move units with a single tap", settings.singleTapMove) { settings.singleTapMove = it }
        addCheckbox("Auto-assign city production", settings.autoAssignCityProduction, true) {
            settings.autoAssignCityProduction = it
            if (it && previousScreen is WorldScreen &&
                previousScreen.viewingCiv.isCurrentPlayer() && previousScreen.viewingCiv.playerType == PlayerType.Human) {
                previousScreen.gameInfo.currentPlayerCiv.cities.forEach { city ->
                    city.cityConstructions.chooseNextConstruction()
                }
            }
        }
        addCheckbox("Auto-build roads", settings.autoBuildingRoads) { settings.autoBuildingRoads = it }
        addCheckbox("Automated workers replace improvements", settings.automatedWorkersReplaceImprovements) { settings.automatedWorkersReplaceImprovements = it }
        addCheckbox("Order trade offers by amount", settings.orderTradeOffersByAmount) { settings.orderTradeOffersByAmount = it }
    }

    private fun getSoundTab() = Table(BaseScreen.skin).apply {
        pad(10f)
        defaults().pad(5f)

        addSoundEffectsVolumeSlider()

        if (previousScreen.game.musicController.isMusicAvailable()) {
            addMusicVolumeSlider()
            addMusicPauseSlider()
            addMusicCurrentlyPlaying()
        } else {
            addDownloadMusic()
        }
    }

    private fun getMultiplayerTab(): Table = Table(BaseScreen.skin).apply {
        pad(10f)
        defaults().pad(5f)

        // at the moment the notification service only exists on Android
        if (Gdx.app.type == Application.ApplicationType.Android) {
            addCheckbox("Enable out-of-game turn notifications",
                settings.multiplayerTurnCheckerEnabled) {
                settings.multiplayerTurnCheckerEnabled = it
                settings.save()
                tabs.replacePage("Multiplayer", getMultiplayerTab())
            }

            if (settings.multiplayerTurnCheckerEnabled) {
                addMultiplayerTurnCheckerDelayBox()

                addCheckbox("Show persistent notification for turn notifier service",
                    settings.multiplayerTurnCheckerPersistentNotificationEnabled)
                { settings.multiplayerTurnCheckerPersistentNotificationEnabled = it }
            }
        }

        val connectionToServerButton = "Check connection to server".toTextButton()

        val ipAddress = getIpAddress()
        add("{Current IP address}: $ipAddress".toTextButton().onClick { 
            Gdx.app.clipboard.contents = ipAddress.toString()
        }).row()

        val multiplayerServerTextField = TextField(settings.multiplayerServer, BaseScreen.skin)
        multiplayerServerTextField.programmaticChangeEvents = true
        val serverIpTable = Table()

        serverIpTable.add("Server's IP address".toLabel().onClick { 
            multiplayerServerTextField.text = Gdx.app.clipboard.contents
        }).padRight(10f)
        multiplayerServerTextField.onChange { 
            settings.multiplayerServer = multiplayerServerTextField.text
            settings.save()
            connectionToServerButton.isEnabled = multiplayerServerTextField.text != Constants.dropboxMultiplayerServer
        }
        serverIpTable.add(multiplayerServerTextField)
        add(serverIpTable).row()

        add("Reset to Dropbox".toTextButton().onClick {
            multiplayerServerTextField.text = Constants.dropboxMultiplayerServer
        }).row()

        add(connectionToServerButton.onClick {
            val popup = Popup(screen).apply { 
                addGoodSizedLabel("Awaiting response...").row()
            }
            popup.open(true)

            successfullyConnectedToServer { success: Boolean, result: String ->
                if (success) {
                    popup.addGoodSizedLabel("Success!").row()
                    popup.addCloseButton()
                } else {
                    popup.addGoodSizedLabel("Failed!").row()
                    popup.addCloseButton()
                }
            }
        }).row()
    }

    fun getIpAddress(): String? {
        DatagramSocket().use { socket ->
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002)
            return socket.localAddress.hostAddress
        }
    }

    object SimpleHttp {
        fun sendGetRequest(url: String, action: (success: Boolean, result: String)->Unit) {
            sendRequest(Net.HttpMethods.GET, url, "", action)
        }

        fun sendRequest(method: String, url: String, content: String, action: (success: Boolean, result: String)->Unit) {
            with(URL(url).openConnection() as HttpURLConnection) {
                requestMethod = method  // default is GET

                doOutput = true

                try {
                    if (content.isNotEmpty()) {
                        // StandardCharsets.UTF_8 requires API 19
                        val postData: ByteArray = content.toByteArray(Charset.forName("UTF-8"))
                        val outputStream = DataOutputStream(outputStream)
                        outputStream.write(postData)
                        outputStream.flush()
                    }

                    val text = BufferedReader(InputStreamReader(inputStream)).readText()
                    action(true, text)
                } catch (t: Throwable) {
                    println(t.message)
                    val errorMessageToReturn =
                        if (errorStream != null) BufferedReader(InputStreamReader(errorStream)).readText()
                        else t.message!!
                    println(errorMessageToReturn)
                    action(false, errorMessageToReturn)
                }
            }
        }

    }
    
    fun successfullyConnectedToServer(action: (Boolean, String)->Unit){
        SimpleHttp.sendGetRequest("http://${settings.multiplayerServer.checkMultiplayerServerWithPort()}/isalive", action)
    }

    private fun getAdvancedTab() = Table(BaseScreen.skin).apply {
        pad(10f)
        defaults().pad(5f)

        addAutosaveTurnsSelectBox()

        addCheckbox("{Show experimental world wrap for maps}\n{HIGHLY EXPERIMENTAL - YOU HAVE BEEN WARNED!}",
            settings.showExperimentalWorldWrap) {
            settings.showExperimentalWorldWrap = it
        }

        addMaxZoomSlider()

        if (previousScreen.game.limitOrientationsHelper != null) {
            addCheckbox("Enable portrait orientation", settings.allowAndroidPortrait) {
                settings.allowAndroidPortrait = it
                // Note the following might close the options screen indirectly and delayed
                previousScreen.game.limitOrientationsHelper.allowPortrait(it)
            }
        }

        addFontFamilySelect(Fonts.getAvailableFontFamilyNames())

        addTranslationGeneration()

        addSetUserId()
    }

    private fun getModCheckTab() = Table(BaseScreen.skin).apply {
        defaults().pad(10f).align(Align.top)
        val reloadModsButton = "Reload mods".toTextButton().onClick {
            runModChecker(modCheckBaseSelect!!.selected.value)
        }
        add(reloadModsButton).row()

        val labeledBaseSelect = Table(BaseScreen.skin).apply {
            add("Check extension mods based on:".toLabel()).padRight(10f)
            val baseMods = listOf(modCheckWithoutBase) + RulesetCache.getSortedBaseRulesets()
            modCheckBaseSelect = TranslatedSelectBox(baseMods, modCheckWithoutBase, BaseScreen.skin).apply {
                selectedIndex = 0
                onChange {
                    runModChecker(modCheckBaseSelect!!.selected.value)
                }
            }
            add(modCheckBaseSelect)
        }
        add(labeledBaseSelect).row()

        add(modCheckResultTable)
    }

    private fun runModChecker(base: String = modCheckWithoutBase) {

        modCheckFirstRun = false
        if (modCheckBaseSelect == null) return

        modCheckResultTable.clear()

        val rulesetErrors = RulesetCache.loadRulesets()
        if (rulesetErrors.isNotEmpty()) {
            val errorTable = Table().apply { defaults().pad(2f) }
            for (rulesetError in rulesetErrors)
                errorTable.add(rulesetError.toLabel()).width(stage.width / 2).row()
            modCheckResultTable.add(errorTable)
        }

        modCheckResultTable.add("Checking mods for errors...".toLabel()).row()
        modCheckBaseSelect!!.isDisabled = true

        crashHandlingThread(name="ModChecker") {
            for (mod in RulesetCache.values.sortedBy { it.name }) {
                if (base != modCheckWithoutBase && mod.modOptions.isBaseRuleset) continue

                val modLinks =
                        if (base == modCheckWithoutBase) mod.checkModLinks(forOptionsPopup = true)
                        else RulesetCache.checkCombinedModLinks(linkedSetOf(mod.name), base, forOptionsPopup = true)
                modLinks.sortByDescending { it.errorSeverityToReport }
                val noProblem = !modLinks.isNotOK()
                if (modLinks.isNotEmpty()) modLinks += RulesetError("", RulesetErrorSeverity.OK)
                if (noProblem) modLinks += RulesetError("No problems found.".tr(), RulesetErrorSeverity.OK)

                postCrashHandlingRunnable {
                    // When the options popup is already closed before this postRunnable is run,
                    // Don't add the labels, as otherwise the game will crash
                    if (stage == null) return@postCrashHandlingRunnable
                    // Don't just render text, since that will make all the conditionals in the mod replacement messages move to the end, which makes it unreadable
                    // Don't use .toLabel() either, since that activates translations as well, which is what we're trying to avoid,
                    // Instead, some manual work needs to be put in.

                    val iconColor = modLinks.getFinalSeverity().color
                    val iconName = when(iconColor) {
                        Color.RED -> "OtherIcons/Stop"
                        Color.YELLOW -> "OtherIcons/ExclamationMark"
                        else -> "OtherIcons/Checkmark"
                    }
                    val icon = ImageGetter.getImage(iconName)
                        .apply { color = Color.BLACK }
                        .surroundWithCircle(30f, color = iconColor)

                    val expanderTab = ExpanderTab(mod.name, icon = icon, startsOutOpened = false) {
                        it.defaults().align(Align.left)
                        if (!noProblem && mod.folderLocation != null) {
                            val replaceableUniques = getDeprecatedReplaceableUniques(mod)
                            if (replaceableUniques.isNotEmpty())
                                it.add("Autoupdate mod uniques".toTextButton()
                                    .onClick { autoUpdateUniques(mod, replaceableUniques) }).pad(10f).row()
                        }
                        for (line in modLinks) {
                            val label = Label(line.text, BaseScreen.skin)
                                .apply { color = line.errorSeverityToReport.color }
                            label.wrap = true
                            it.add(label).width(stage.width / 2).row()
                        }
                        if (!noProblem)
                            it.add("Copy to clipboard".toTextButton().onClick {
                                Gdx.app.clipboard.contents = modLinks
                                    .joinToString("\n") { line -> line.text }
                            }).row()
                    }

                    val loadingLabel = modCheckResultTable.children.last()
                    modCheckResultTable.removeActor(loadingLabel)
                    modCheckResultTable.add(expanderTab).row()
                    modCheckResultTable.add(loadingLabel).row()
                }
            }

            // done with all mods!
            postCrashHandlingRunnable {
                modCheckResultTable.removeActor(modCheckResultTable.children.last())
                modCheckBaseSelect!!.isDisabled = false
            }
        }
    }

    private fun getDeprecatedReplaceableUniques(mod:Ruleset): HashMap<String, String> {

        val objectsToCheck = sequenceOf(
            mod.beliefs,
            mod.buildings,
            mod.nations,
            mod.policies,
            mod.technologies,
            mod.terrains,
            mod.tileImprovements,
            mod.unitPromotions,
            mod.unitTypes,
            mod.units,
        )
        val allDeprecatedUniques = HashSet<String>()
        val deprecatedUniquesToReplacementText = HashMap<String, String>()

        val deprecatedUniques = objectsToCheck
            .flatMap { it.values }
            .flatMap { it.uniqueObjects }
            .filter { it.getDeprecationAnnotation() != null }


        for (deprecatedUnique in deprecatedUniques) {
            if (allDeprecatedUniques.contains(deprecatedUnique.text)) continue
            allDeprecatedUniques.add(deprecatedUnique.text)

            // note that this replacement does not contain conditionals attached to the original!


            var uniqueReplacementText = deprecatedUnique.getReplacementText(mod)
            while (Unique(uniqueReplacementText).getDeprecationAnnotation() != null)
                uniqueReplacementText = Unique(uniqueReplacementText).getReplacementText(mod)

            for (conditional in deprecatedUnique.conditionals)
                uniqueReplacementText += " <${conditional.text}>"
            val replacementUnique = Unique(uniqueReplacementText)

            val modInvariantErrors = mod.checkUnique(
                replacementUnique,
                false,
                "",
                UniqueType.UniqueComplianceErrorSeverity.RulesetInvariant,
                deprecatedUnique.sourceObjectType!!
            )
            for (error in modInvariantErrors)
                println(error.text + " - " + error.errorSeverityToReport)
            if (modInvariantErrors.isNotEmpty()) continue // errors means no autoreplace

            if (mod.modOptions.isBaseRuleset) {
                val modSpecificErrors = mod.checkUnique(
                    replacementUnique,
                    false,
                    "",
                    UniqueType.UniqueComplianceErrorSeverity.RulesetInvariant,
                    deprecatedUnique.sourceObjectType
                )
                for (error in modSpecificErrors)
                    println(error.text + " - " + error.errorSeverityToReport)
                if (modSpecificErrors.isNotEmpty()) continue
            }

            deprecatedUniquesToReplacementText[deprecatedUnique.text] = uniqueReplacementText
            println("Replace \"${deprecatedUnique.text}\" with \"$uniqueReplacementText\"")
        }
        return deprecatedUniquesToReplacementText
    }

    private fun autoUpdateUniques(mod: Ruleset, replaceableUniques: HashMap<String, String>) {

        if (mod.name.contains("mod"))
            println("mod")

        val filesToReplace = listOf(
            "Beliefs.json",
            "Buildings.json",
            "Nations.json",
            "Policies.json",
            "Techs.json",
            "Terrains.json",
            "TileImprovements.json",
            "UnitPromotions.json",
            "UnitTypes.json",
            "Units.json",
        )

        val jsonFolder = mod.folderLocation!!.child("jsons")
        for (fileName in filesToReplace) {
            val file = jsonFolder.child(fileName)
            if (!file.exists() || file.isDirectory) continue
            var newFileText = file.readString()
            for ((original, replacement) in replaceableUniques) {
                newFileText = newFileText.replace("\"$original\"", "\"$replacement\"")
            }
            file.writeString(newFileText, false)
        }
        val toastText = "Uniques updated!"
        ToastPopup(toastText, screen).open(true)
        runModChecker()
    }

    private fun getDebugTab() = Table(BaseScreen.skin).apply {
        pad(10f)
        defaults().pad(5f)

        val game = UncivGame.Current
        add("Supercharged".toCheckBox(game.superchargedForDebug) {
            game.superchargedForDebug = it
        }).row()
        add("View entire map".toCheckBox(game.viewEntireMapForDebug) {
            game.viewEntireMapForDebug = it
        }).row()
        if (game.isGameInfoInitialized()) {
            add("God mode (current game)".toCheckBox(game.gameInfo.gameParameters.godMode) {
                game.gameInfo.gameParameters.godMode = it
            }).row()
        }
        add("Save maps compressed".toCheckBox(MapSaver.saveZipped) {
            MapSaver.saveZipped = it
        }).row()
        add("Gdx Scene2D debug".toCheckBox(BaseScreen.enableSceneDebug) {
            BaseScreen.enableSceneDebug = it
        }).row()

        add("Allow untyped Uniques in mod checker".toCheckBox(RulesetCache.modCheckerAllowUntypedUniques) {
            RulesetCache.modCheckerAllowUntypedUniques = it
        }).row()

        add(Table().apply {
            add("Unique misspelling threshold".toLabel()).left().fillX()
            add(
                UncivSlider(0f, 0.5f, 0.05f, initial = RulesetCache.uniqueMisspellingThreshold.toFloat()) {
                    RulesetCache.uniqueMisspellingThreshold = it.toDouble()
                }
            ).minWidth(120f).pad(5f)
        }).row()

        val unlockTechsButton = "Unlock all techs".toTextButton()
        unlockTechsButton.onClick {
            if (!game.isGameInfoInitialized())
                return@onClick
            for (tech in game.gameInfo.ruleSet.technologies.keys) {
                if (tech !in game.gameInfo.getCurrentPlayerCivilization().tech.techsResearched) {
                    game.gameInfo.getCurrentPlayerCivilization().tech.addTechnology(tech)
                    game.gameInfo.getCurrentPlayerCivilization().popupAlerts.removeLastOrNull()
                }
            }
            game.gameInfo.getCurrentPlayerCivilization().updateSightAndResources()
            game.worldScreen.shouldUpdate = true
        }
        add(unlockTechsButton).row()

        val giveResourcesButton = "Give all strategic resources".toTextButton()
        giveResourcesButton.onClick {
            if (!game.isGameInfoInitialized())
                return@onClick
            val ownedTiles = game.gameInfo.tileMap.values.asSequence().filter { it.getOwner() == game.gameInfo.getCurrentPlayerCivilization() }
            val resourceTypes = game.gameInfo.ruleSet.tileResources.values.asSequence().filter { it.resourceType == ResourceType.Strategic }
            for ((tile, resource) in ownedTiles zip resourceTypes) {
                tile.resource = resource.name
                tile.resourceAmount = 999
                tile.improvement = resource.improvement
            }
            game.gameInfo.getCurrentPlayerCivilization().updateSightAndResources()
            game.worldScreen.shouldUpdate = true
        }
        add(giveResourcesButton).row()
    }

    //endregion
    //region Row builders

    private fun Table.addMinimapSizeSlider() {
        add("Show minimap".toLabel()).left().fillX()

        // The meaning of the values needs a formula to be synchronized between here and
        // [Minimap.init]. It goes off-10%-11%..29%-30%-35%-40%-45%-50% - and the percentages
        // correspond roughly to the minimap's proportion relative to screen dimensions.
        val offTranslated = "off".tr()  // translate only once and cache in closure
        val getTipText: (Float)->String = {
            when (it) {
                0f -> offTranslated
                in 0.99f..21.01f -> "%.0f".format(it+9) + "%"
                else -> "%.0f".format(it * 5 - 75) + "%"
            }
        }
        val minimapSlider = UncivSlider(0f, 25f, 1f,
            initial = if (settings.showMinimap) settings.minimapSize.toFloat() else 0f,
            getTipText = getTipText
        ) {
            val size = it.toInt()
            if (size == 0) settings.showMinimap = false
            else {
                settings.showMinimap = true
                settings.minimapSize = size
            }
            settings.save()
            if (previousScreen is WorldScreen)
                previousScreen.shouldUpdate = true
        }
        add(minimapSlider).minWidth(selectBoxMinWidth).pad(10f).row()
    }

    private fun Table.addResolutionSelectBox() {
        add("Resolution".toLabel()).left().fillX()

        val resolutionSelectBox = SelectBox<String>(skin)
        resolutionSelectBox.items = resolutionArray
        resolutionSelectBox.selected = settings.resolution
        add(resolutionSelectBox).minWidth(selectBoxMinWidth).pad(10f).row()

        resolutionSelectBox.onChange {
            settings.resolution = resolutionSelectBox.selected
            reloadWorldAndOptions()
        }
    }

    private fun Table.addTileSetSelectBox() {
        add("Tileset".toLabel()).left().fillX()

        val tileSetSelectBox = SelectBox<String>(skin)
        val tileSetArray = GdxArray<String>()
        val tileSets = ImageGetter.getAvailableTilesets()
        for (tileset in tileSets) tileSetArray.add(tileset)
        tileSetSelectBox.items = tileSetArray
        tileSetSelectBox.selected = settings.tileSet
        add(tileSetSelectBox).minWidth(selectBoxMinWidth).pad(10f).row()

        tileSetSelectBox.onChange {
            settings.tileSet = tileSetSelectBox.selected
            // ImageGetter ruleset should be correct no matter what screen we're on
            TileSetCache.assembleTileSetConfigs(ImageGetter.ruleset.mods)
            reloadWorldAndOptions()
        }
    }

    private fun Table.addSoundEffectsVolumeSlider() {
        add("Sound effects volume".tr()).left().fillX()

        val soundEffectsVolumeSlider = UncivSlider(0f, 1.0f, 0.05f,
            initial = settings.soundEffectsVolume,
            getTipText = UncivSlider::formatPercent
        ) {
            settings.soundEffectsVolume = it
            settings.save()
        }
        add(soundEffectsVolumeSlider).pad(5f).row()
    }

    private fun Table.addMusicVolumeSlider() {
        add("Music volume".tr()).left().fillX()

        val musicVolumeSlider = UncivSlider(0f, 1.0f, 0.05f,
            initial = settings.musicVolume,
            sound = UncivSound.Silent,
            getTipText = UncivSlider::formatPercent
        ) {
            settings.musicVolume = it
            settings.save()

            val music = previousScreen.game.musicController
            music.setVolume(it)
            if (!music.isPlaying())
                music.chooseTrack(flags = MusicTrackChooserFlags.setPlayDefault)
        }
        add(musicVolumeSlider).pad(5f).row()
    }

    private fun Table.addMusicPauseSlider() {
        val music = previousScreen.game.musicController

        // map to/from 0-1-2..10-12-14..30-35-40..60-75-90-105-120
        fun posToLength(pos: Float): Float = when (pos) {
            in 0f..10f -> pos
            in 11f..20f -> pos * 2f - 10f
            in 21f..26f -> pos * 5f - 70f
            else -> pos * 15f - 330f
        }
        fun lengthToPos(length: Float): Float = floor(when (length) {
            in 0f..10f -> length
            in 11f..30f -> (length + 10f) / 2f
            in 31f..60f -> (length + 10f) / 5f
            else -> (length + 330f) / 15f
        })
        val getTipText: (Float)->String = {
            "%.0f".format(posToLength(it))
        }

        add("Pause between tracks".tr()).left().fillX()

        val pauseLengthSlider = UncivSlider(0f, 30f, 1f,
            initial = lengthToPos(music.silenceLength),
            sound = UncivSound.Silent,
            getTipText = getTipText
        ) {
            music.silenceLength = posToLength(it)
            settings.pauseBetweenTracks = music.silenceLength.toInt()
        }
        add(pauseLengthSlider).pad(5f).row()
    }

    private fun Table.addMusicCurrentlyPlaying() {
        val label = WrappableLabel("", this.width - 10f, Color(-0x2f5001), 16)
        label.wrap = true
        add(label).padTop(20f).colspan(2).fillX().row()
        previousScreen.game.musicController.onChange {
            postCrashHandlingRunnable {
                label.setText("Currently playing: [$it]".tr())
            }
        }
        label.onClick { previousScreen.game.musicController.chooseTrack(flags = MusicTrackChooserFlags.setNextTurn) }
    }

    private fun Table.addDownloadMusic() {
        val downloadMusicButton = "Download music".toTextButton()
        add(downloadMusicButton).colspan(2).row()
        val errorTable = Table()
        add(errorTable).colspan(2).row()

        downloadMusicButton.onClick {
            downloadMusicButton.disable()
            errorTable.clear()
            errorTable.add("Downloading...".toLabel())

            // So the whole game doesn't get stuck while downloading the file
            crashHandlingThread(name = "Music") {
                try {
                    previousScreen.game.musicController.downloadDefaultFile()
                    postCrashHandlingRunnable {
                        tabs.replacePage("Sound", getSoundTab())
                        previousScreen.game.musicController.chooseTrack(flags = MusicTrackChooserFlags.setPlayDefault)
                    }
                } catch (ex: Exception) {
                    postCrashHandlingRunnable {
                        errorTable.clear()
                        errorTable.add("Could not download music!".toLabel(Color.RED))
                    }
                }
            }
        }
    }

    private fun Table.addMultiplayerTurnCheckerDelayBox() {
        add("Time between turn checks out-of-game (in minutes)".toLabel()).left().fillX()

        val checkDelaySelectBox = SelectBox<Int>(skin)
        val possibleDelaysArray = GdxArray<Int>()
        possibleDelaysArray.addAll(1, 2, 5, 15)
        checkDelaySelectBox.items = possibleDelaysArray
        checkDelaySelectBox.selected = settings.multiplayerTurnCheckerDelayInMinutes

        add(checkDelaySelectBox).pad(10f).row()

        checkDelaySelectBox.onChange {
            settings.multiplayerTurnCheckerDelayInMinutes = checkDelaySelectBox.selected
            settings.save()
        }
    }

    private fun Table.addSetUserId() {
        val idSetLabel = "".toLabel()
        val takeUserIdFromClipboardButton = "Take user ID from clipboard".toTextButton()
            .onClick {
                try {
                    val clipboardContents = Gdx.app.clipboard.contents.trim()
                    UUID.fromString(clipboardContents)
                    YesNoPopup("Doing this will reset your current user ID to the clipboard contents - are you sure?",
                        {
                            settings.userId = clipboardContents
                            settings.save()
                            idSetLabel.setFontColor(Color.WHITE).setText("ID successfully set!".tr())
                        }, previousScreen).open(true)
                    idSetLabel.isVisible = true
                } catch (ex: Exception) {
                    idSetLabel.isVisible = true
                    idSetLabel.setFontColor(Color.RED).setText("Invalid ID!".tr())
                }
            }
        add(takeUserIdFromClipboardButton).pad(5f).colspan(2).row()
        add(idSetLabel).colspan(2).row()
    }

    private fun Table.addAutosaveTurnsSelectBox() {
        add("Turns between autosaves".toLabel()).left().fillX()

        val autosaveTurnsSelectBox = SelectBox<Int>(skin)
        val autosaveTurnsArray = GdxArray<Int>()
        autosaveTurnsArray.addAll(1, 2, 5, 10)
        autosaveTurnsSelectBox.items = autosaveTurnsArray
        autosaveTurnsSelectBox.selected = settings.turnsBetweenAutosaves

        add(autosaveTurnsSelectBox).pad(10f).row()

        autosaveTurnsSelectBox.onChange {
            settings.turnsBetweenAutosaves = autosaveTurnsSelectBox.selected
            settings.save()
        }
    }

    private fun Table.addMaxZoomSlider() {
        add("Max zoom out".tr()).left().fillX()
        val maxZoomSlider = UncivSlider(2f, 6f, 1f,
            initial = settings.maxWorldZoomOut
        ) {
            settings.maxWorldZoomOut = it
            settings.save()
        }
        add(maxZoomSlider).pad(5f).row()
    }

    private fun Table.addFontFamilySelect(fonts: Collection<FontData>) {
        if (fonts.isEmpty()) return

        add("Font family".toLabel()).left().fillX()

        val fontSelectBox = SelectBox<String>(skin)
        val fontsLocalName = GdxArray<String>().apply { add("Default Font".tr()) }
        val fontsEnName = GdxArray<String>().apply { add("") }
        for (font in fonts) {
            fontsLocalName.add(font.localName)
            fontsEnName.add(font.enName)
        }

        val selectedIndex = fontsEnName.indexOf(settings.fontFamily).let { if (it == -1) 0 else it }

        fontSelectBox.items = fontsLocalName
        fontSelectBox.selected = fontsLocalName[selectedIndex]

        add(fontSelectBox).minWidth(selectBoxMinWidth).pad(10f).row()

        fontSelectBox.onChange {
            settings.fontFamily = fontsEnName[fontSelectBox.selectedIndex]
            ToastPopup(
                "You need to restart the game for this change to take effect.", previousScreen
            )
        }
    }

    private fun Table.addTranslationGeneration() {
        if (Gdx.app.type == Application.ApplicationType.Desktop) {
            val generateTranslationsButton = "Generate translation files".toTextButton()
            val generateAction = {
                val result = TranslationFileWriter.writeNewTranslationFiles()
                // notify about completion
                generateTranslationsButton.setText(result.tr())
                generateTranslationsButton.disable()
            }
            generateTranslationsButton.onClick(generateAction)
            keyPressDispatcher[Input.Keys.F12] = generateAction
            generateTranslationsButton.addTooltip("F12",18f)
            add(generateTranslationsButton).colspan(2).row()
        }
    }

    private fun Table.addCheckbox(text: String, initialState: Boolean, updateWorld: Boolean = false, action: ((Boolean) -> Unit)) {
        val checkbox = text.toCheckBox(initialState) {
            action(it)
            settings.save()
            if (updateWorld && previousScreen is WorldScreen)
                previousScreen.shouldUpdate = true
        }
        add(checkbox).colspan(2).left().row()
    }

    //endregion
}
