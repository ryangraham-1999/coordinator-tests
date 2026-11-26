package com.streamlinemyjob.inspect.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streamlinemyjob.inspect.AppContainer
import com.streamlinemyjob.inspect.data.Dot

/** Routes of the screens in designs/stage-1-foundation.md 4.16 built so far. */
object Routes {
    const val VEHICLES = "vehicles"
    const val VEHICLE_FORM = "vehicle-form?vehicleId={vehicleId}"
    const val VEHICLE = "vehicle/{vehicleId}"
    const val INSPECTION_FORM = "inspection-form/{vehicleId}?inspectionId={inspectionId}"
    const val INSPECTION = "inspection/{inspectionId}"
    const val FINDING = "finding/{inspectionId}?findingId={findingId}&dotX={dotX}&dotY={dotY}"
    const val SETTINGS = "settings"
    const val LIST_EDITOR = "list/{listId}"
    const val REGION_LINKS = "region-links"

    fun vehicleForm(vehicleId: String? = null) = if (vehicleId == null) "vehicle-form" else "vehicle-form?vehicleId=$vehicleId"
    fun vehicle(vehicleId: String) = "vehicle/$vehicleId"
    fun inspectionForm(vehicleId: String, inspectionId: String? = null) =
        if (inspectionId == null) "inspection-form/$vehicleId" else "inspection-form/$vehicleId?inspectionId=$inspectionId"
    fun inspection(inspectionId: String) = "inspection/$inspectionId"
    fun listEditor(listId: String) = "list/$listId"
    fun finding(inspectionId: String, findingId: String? = null) =
        if (findingId == null) "finding/$inspectionId" else "finding/$inspectionId?findingId=$findingId"

    /** A new finding that starts with a dot made by a tap on the Inspection screen's drawing (4.28). */
    fun findingAt(inspectionId: String, dot: Dot) = "finding/$inspectionId?dotX=${dot.x}&dotY=${dot.y}"
}

@Composable
fun CoordinatorNav(container: AppContainer, nav: NavHostController = rememberNavController()) {
    val optional = { name: String -> navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null } }
    NavHost(navController = nav, startDestination = Routes.VEHICLES) {
        composable(Routes.VEHICLES) {
            VehiclesScreen(
                container,
                openVehicle = { nav.navigate(Routes.vehicle(it)) },
                newVehicle = { nav.navigate(Routes.vehicleForm()) },
                openSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                container,
                openList = { nav.navigate(Routes.listEditor(it)) },
                openRegionLinks = { nav.navigate(Routes.REGION_LINKS) },
            )
        }
        composable(Routes.REGION_LINKS) { RegionLinksScreen(container) }
        composable(Routes.LIST_EDITOR, arguments = listOf(navArgument("listId") { type = NavType.StringType })) { entry ->
            ListEditorScreen(container, entry.arguments!!.getString("listId")!!)
        }
        composable(Routes.VEHICLE_FORM, arguments = listOf(optional("vehicleId"))) { entry ->
            val editing = entry.arguments?.getString("vehicleId")
            VehicleFormScreen(
                container,
                vehicleId = editing,
                saved = { id ->
                    if (editing == null) {
                        nav.navigate(Routes.vehicle(id)) { popUpTo(Routes.VEHICLE_FORM) { inclusive = true } }
                    } else {
                        nav.popBackStack()
                    }
                },
                openExisting = { id ->
                    nav.navigate(Routes.vehicle(id)) { popUpTo(Routes.VEHICLES) }
                },
            )
        }
        composable(Routes.VEHICLE, arguments = listOf(navArgument("vehicleId") { type = NavType.StringType })) { entry ->
            val vehicleId = entry.arguments!!.getString("vehicleId")!!
            VehicleScreen(
                container,
                vehicleId,
                edit = { nav.navigate(Routes.vehicleForm(vehicleId)) },
                deleted = { nav.popBackStack(Routes.VEHICLES, inclusive = false) },
                openInspection = { nav.navigate(Routes.inspection(it)) },
                newInspection = { nav.navigate(Routes.inspectionForm(vehicleId)) },
            )
        }
        composable(
            Routes.INSPECTION_FORM,
            arguments = listOf(navArgument("vehicleId") { type = NavType.StringType }, optional("inspectionId")),
        ) { entry ->
            val vehicleId = entry.arguments!!.getString("vehicleId")!!
            val editing = entry.arguments?.getString("inspectionId")
            InspectionFormScreen(
                container,
                vehicleId = vehicleId,
                inspectionId = editing,
                saved = { id ->
                    if (editing == null) {
                        nav.navigate(Routes.inspection(id)) { popUpTo(Routes.INSPECTION_FORM) { inclusive = true } }
                    } else {
                        nav.popBackStack()
                    }
                },
            )
        }
        composable(Routes.INSPECTION, arguments = listOf(navArgument("inspectionId") { type = NavType.StringType })) { entry ->
            val inspectionId = entry.arguments!!.getString("inspectionId")!!
            InspectionScreen(
                container,
                inspectionId,
                edit = { vehicleId -> nav.navigate(Routes.inspectionForm(vehicleId, inspectionId)) },
                deleted = { nav.popBackStack() },
                addFinding = { nav.navigate(Routes.finding(inspectionId)) },
                openFinding = { nav.navigate(Routes.finding(inspectionId, it)) },
                addFindingAt = { nav.navigate(Routes.findingAt(inspectionId, it)) },
            )
        }
        composable(
            Routes.FINDING,
            arguments = listOf(navArgument("inspectionId") { type = NavType.StringType }, optional("findingId"), optional("dotX"), optional("dotY")),
        ) { entry ->
            val inspectionId = entry.arguments!!.getString("inspectionId")!!
            val x = entry.arguments?.getString("dotX")?.toDoubleOrNull()
            val y = entry.arguments?.getString("dotY")?.toDoubleOrNull()
            FindingEditorScreen(
                container, inspectionId, entry.arguments?.getString("findingId"), done = { nav.popBackStack() },
                startDot = if (x != null && y != null) Dot(x, y) else null,
            )
        }
    }
}
