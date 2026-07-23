package app.vela.car.screen

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import app.vela.R

/** One-tap category search (Gas / Food / Coffee / Groceries) - the same four the phone offers
 *  along a route, with the same dual-purpose-literal rule: the LABEL localizes, the search QUERY
 *  stays the stable English key. From the landing screen a tap searches nearby; from active nav
 *  ([alongRoute]) results come corridor-filtered and a pick adds a stop. */
class CategoriesCarScreen(
    carContext: CarContext,
    private val deps: CarDeps,
    private val alongRoute: Boolean = false,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        listOf(
            Triple(R.string.cat_gas, "Gas", R.drawable.ic_car_gas),
            Triple(R.string.cat_food, "Food", R.drawable.ic_car_food),
            Triple(R.string.cat_coffee, "Coffee", R.drawable.ic_car_coffee),
            Triple(R.string.cat_groceries, "Groceries", R.drawable.ic_car_grocery),
        ).forEach { (labelRes, query, iconRes) ->
            list.addItem(
                GridItem.Builder()
                    .setTitle(carContext.getString(labelRes))
                    .setImage(
                        CarIcon.Builder(IconCompat.createWithResource(carContext, iconRes)).build(),
                        GridItem.IMAGE_TYPE_ICON,
                    )
                    .setOnClickListener {
                        screenManager.push(SearchCarScreen(carContext, deps, query, alongRoute))
                    }
                    .build(),
            )
        }
        return GridTemplate.Builder()
            .setTitle(carContext.getString(R.string.car_categories))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
