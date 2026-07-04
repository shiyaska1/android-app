package com.billing.pos.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter fun roleToString(role: Role): String = role.name
    @TypeConverter fun stringToRole(value: String): Role = Role.valueOf(value)

    @TypeConverter fun attTypeToString(type: AttachmentType): String = type.name
    @TypeConverter fun stringToAttType(value: String): AttachmentType = AttachmentType.valueOf(value)
}
