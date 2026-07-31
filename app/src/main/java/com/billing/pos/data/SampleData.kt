package com.billing.pos.data

/** A ready-to-load sample item for a business type. */
data class SampleItem(val name: String, val category: String, val price: Double, val unit: String = "PCS", val chemical: String = "")

/** Curated starter item lists per business type, loaded on demand from Settings. */
object SampleData {

    fun itemsFor(type: String): List<SampleItem> = when (type) {
        "Restaurant" -> restaurant
        "Grocery" -> grocery
        "Medical store" -> medical
        "Textiles" -> textiles
        "Mobile shop" -> mobile
        "Electrical & plumbing" -> electrical
        "Automobiles" -> automobiles
        "General" -> general
        "Rental" -> rental
        "Medical lab" -> medicalLab
        "Bulk SMS" -> bulkSms
        "Gym" -> gym
        "Coaching Center" -> coaching
        "Service Center" -> serviceCenter
        else -> emptyList()
    }

    private val restaurant = listOf(
        SampleItem("Veg Manchurian", "Starters", 120.0),
        SampleItem("Chicken 65", "Starters", 180.0),
        SampleItem("Paneer Butter Masala", "Main Course", 220.0),
        SampleItem("Butter Chicken", "Main Course", 260.0),
        SampleItem("Butter Naan", "Breads", 40.0),
        SampleItem("Tandoori Roti", "Breads", 20.0),
        SampleItem("Veg Biryani", "Rice", 160.0),
        SampleItem("Chicken Biryani", "Rice", 200.0),
        SampleItem("Masala Dosa", "South Indian", 90.0),
        SampleItem("Cold Coffee", "Beverages", 90.0),
        SampleItem("Gulab Jamun", "Desserts", 60.0)
    )

    private val grocery = listOf(
        SampleItem("Rice 1kg", "Grains", 60.0, "KG"),
        SampleItem("Wheat Flour 1kg", "Grains", 45.0, "KG"),
        SampleItem("Sugar 1kg", "Grains", 45.0, "KG"),
        SampleItem("Toor Dal 1kg", "Grains", 120.0, "KG"),
        SampleItem("Sunflower Oil 1L", "Oils", 140.0, "LTR"),
        SampleItem("Salt 1kg", "Spices", 25.0, "KG"),
        SampleItem("Turmeric 100g", "Spices", 30.0),
        SampleItem("Tea Powder 250g", "Beverages", 130.0),
        SampleItem("Milk 500ml", "Dairy", 30.0),
        SampleItem("Biscuits", "Snacks", 20.0)
    )

    private val medical = listOf(
        SampleItem("Paracetamol 500mg", "Tablets", 25.0, "STRIP", "Paracetamol 500mg"),
        SampleItem("Amoxicillin 500mg", "Tablets", 60.0, "STRIP", "Amoxicillin 500mg"),
        SampleItem("Cetirizine 10mg", "Tablets", 20.0, "STRIP", "Cetirizine 10mg"),
        SampleItem("Azithromycin 500mg", "Tablets", 90.0, "STRIP", "Azithromycin 500mg"),
        SampleItem("Pantoprazole 40mg", "Tablets", 55.0, "STRIP", "Pantoprazole 40mg"),
        SampleItem("Metformin 500mg", "Tablets", 35.0, "STRIP", "Metformin HCl 500mg"),
        SampleItem("Amlodipine 5mg", "Tablets", 30.0, "STRIP", "Amlodipine 5mg"),
        SampleItem("Ibuprofen 400mg", "Tablets", 28.0, "STRIP", "Ibuprofen 400mg"),
        SampleItem("Cough Syrup 100ml", "Syrups", 85.0, "BOTTLE", "Dextromethorphan"),
        SampleItem("ORS Sachet", "Others", 15.0, "PCS", "Oral Rehydration Salts")
    )

    private val textiles = listOf(
        SampleItem("Cotton Shirt", "Men", 599.0),
        SampleItem("Formal Trouser", "Men", 899.0),
        SampleItem("Jeans", "Men", 1099.0),
        SampleItem("Saree", "Women", 1299.0),
        SampleItem("Kurti", "Women", 799.0),
        SampleItem("Kids T-Shirt", "Kids", 299.0),
        SampleItem("Bedsheet", "Home", 699.0),
        SampleItem("Towel", "Home", 199.0),
        SampleItem("Cotton Fabric 1m", "Fabric", 150.0, "METER"),
        SampleItem("Silk Fabric 1m", "Fabric", 450.0, "METER")
    )

    private val mobile = listOf(
        SampleItem("Phone Charger", "Accessories", 299.0),
        SampleItem("USB Cable", "Accessories", 149.0),
        SampleItem("Earphones", "Accessories", 399.0),
        SampleItem("Neckband", "Accessories", 799.0),
        SampleItem("Bluetooth Speaker", "Accessories", 999.0),
        SampleItem("Power Bank 10000mAh", "Accessories", 899.0),
        SampleItem("Tempered Glass", "Accessories", 149.0),
        SampleItem("Phone Cover", "Accessories", 199.0),
        SampleItem("Memory Card 32GB", "Accessories", 349.0),
        SampleItem("Smart Watch", "Accessories", 1499.0)
    )

    private val electrical = listOf(
        SampleItem("LED Bulb 9W", "Electrical", 90.0),
        SampleItem("Switch", "Electrical", 45.0),
        SampleItem("Wire 1m", "Electrical", 25.0, "METER"),
        SampleItem("Extension Board", "Electrical", 350.0),
        SampleItem("MCB", "Electrical", 220.0),
        SampleItem("Ceiling Fan", "Electrical", 1499.0),
        SampleItem("PVC Pipe 1m", "Plumbing", 80.0, "METER"),
        SampleItem("Tap", "Plumbing", 250.0),
        SampleItem("Elbow Joint", "Plumbing", 30.0),
        SampleItem("Teflon Tape", "Plumbing", 20.0)
    )

    private val automobiles = listOf(
        SampleItem("Engine Oil 1L", "Oils", 450.0, "LTR"),
        SampleItem("Air Filter", "Spares", 350.0),
        SampleItem("Brake Pad Set", "Spares", 800.0),
        SampleItem("Spark Plug", "Spares", 120.0),
        SampleItem("Clutch Plate", "Spares", 1200.0),
        SampleItem("Headlight Bulb", "Spares", 180.0),
        SampleItem("Battery", "Spares", 3500.0),
        SampleItem("Wiper Blade", "Accessories", 250.0),
        SampleItem("Car Perfume", "Accessories", 150.0),
        SampleItem("Seat Cover", "Accessories", 1200.0)
    )

    private val general = listOf(
        SampleItem("Notebook 200pg", "Stationery", 45.0),
        SampleItem("Ball Pen", "Stationery", 10.0),
        SampleItem("Stapler", "Stationery", 90.0),
        SampleItem("Scissors", "Household", 60.0),
        SampleItem("Glue Stick", "Stationery", 25.0),
        SampleItem("Umbrella", "Household", 350.0),
        SampleItem("LED Torch", "Household", 150.0),
        SampleItem("Batteries AA (4pc)", "Household", 80.0),
        SampleItem("Plastic Bucket", "Household", 180.0),
        SampleItem("Broom", "Household", 90.0)
    )

    private val rental = listOf(
        SampleItem("Plastic Chair", "Furniture", 20.0),
        SampleItem("Round Table", "Furniture", 150.0),
        SampleItem("Canopy Tent 10x10", "Tents", 800.0),
        SampleItem("Generator 5KVA", "Equipment", 1500.0),
        SampleItem("PA Speaker", "Sound", 500.0),
        SampleItem("Wireless Mic Set", "Sound", 300.0),
        SampleItem("LED Par Light", "Lighting", 100.0),
        SampleItem("Carpet Roll", "Decor", 250.0),
        SampleItem("Air Cooler", "Equipment", 400.0),
        SampleItem("Steel Utensil Set", "Catering", 350.0)
    )

    private val medicalLab = listOf(
        SampleItem("Syringe 5ml", "Consumables", 8.0),
        SampleItem("Vacutainer Tube", "Consumables", 12.0),
        SampleItem("Cotton Roll", "Consumables", 40.0),
        SampleItem("Spirit Swab (Box)", "Consumables", 60.0),
        SampleItem("Glucometer Strips", "Consumables", 350.0, "BOX"),
        SampleItem("Face Mask (Box)", "Consumables", 120.0, "BOX"),
        SampleItem("Hand Sanitizer 500ml", "Consumables", 150.0),
        SampleItem("Examination Gloves (Box)", "Consumables", 250.0, "BOX"),
        SampleItem("Bandage Roll", "Consumables", 30.0),
        SampleItem("Digital Thermometer", "Equipment", 200.0)
    )

    private val bulkSms = listOf(
        SampleItem("SMS Credits 1000", "SMS Packs", 250.0),
        SampleItem("SMS Credits 5000", "SMS Packs", 1100.0),
        SampleItem("SMS Credits 10000", "SMS Packs", 2000.0),
        SampleItem("SMS Credits 25000", "SMS Packs", 4500.0),
        SampleItem("WhatsApp Credits 500", "WhatsApp Packs", 500.0),
        SampleItem("WhatsApp Credits 1000", "WhatsApp Packs", 900.0),
        SampleItem("Voice Call Pack 100min", "Voice Packs", 300.0),
        SampleItem("Bulk Email Pack 5000", "Email Packs", 400.0),
        SampleItem("Sender ID Setup", "Services", 500.0),
        SampleItem("DLT Registration Service", "Services", 1000.0)
    )

    private val gym = listOf(
        SampleItem("Monthly Membership", "Membership", 1500.0),
        SampleItem("Quarterly Membership", "Membership", 4000.0),
        SampleItem("Annual Membership", "Membership", 14000.0),
        SampleItem("Personal Training Session", "Training", 500.0),
        SampleItem("Protein Shake", "Supplements", 150.0),
        SampleItem("Gym Gloves", "Accessories", 250.0),
        SampleItem("Shaker Bottle", "Accessories", 200.0),
        SampleItem("Resistance Band", "Accessories", 300.0),
        SampleItem("Skipping Rope", "Accessories", 180.0),
        SampleItem("Gym Towel", "Accessories", 150.0)
    )

    private val coaching = listOf(
        SampleItem("Monthly Tuition Fee", "Fees", 2000.0),
        SampleItem("Course Registration", "Fees", 500.0),
        SampleItem("Study Material Kit", "Materials", 800.0),
        SampleItem("Notebook Set", "Materials", 150.0),
        SampleItem("Test Series Pack", "Fees", 600.0),
        SampleItem("Whiteboard Marker", "Materials", 30.0),
        SampleItem("Mock Test Fee", "Fees", 100.0),
        SampleItem("Uniform", "Materials", 450.0),
        SampleItem("ID Card", "Materials", 50.0),
        SampleItem("Certificate Printing", "Services", 100.0)
    )

    private val serviceCenter = listOf(
        SampleItem("Service Charge - Basic", "Services", 300.0),
        SampleItem("Service Charge - AC", "Services", 600.0),
        SampleItem("Screen Replacement", "Spares", 2500.0),
        SampleItem("Battery Replacement", "Spares", 1200.0),
        SampleItem("Deep Cleaning Service", "Services", 400.0),
        SampleItem("Spare Part - Generic", "Spares", 350.0),
        SampleItem("Diagnostic Fee", "Services", 150.0),
        SampleItem("Home Visit Charge", "Services", 200.0),
        SampleItem("Warranty Extension", "Services", 999.0),
        SampleItem("Labour Charge (per hr)", "Services", 250.0)
    )
}
