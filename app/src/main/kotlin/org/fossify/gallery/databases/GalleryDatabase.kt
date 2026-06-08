package org.fossify.gallery.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.fossify.gallery.interfaces.*
import org.fossify.gallery.models.*

@Database(entities = [Directory::class, Medium::class, Widget::class, DateTaken::class, Favorite::class, VaultItem::class, ImageLabel::class, ImageText::class, ImageEmbedding::class, ImageHash::class, Album::class, AlbumItem::class], version = 19)
abstract class GalleryDatabase : RoomDatabase() {

    abstract fun DirectoryDao(): DirectoryDao

    abstract fun MediumDao(): MediumDao

    abstract fun WidgetsDao(): WidgetsDao

    abstract fun DateTakensDao(): DateTakensDao

    abstract fun FavoritesDao(): FavoritesDao

    abstract fun VaultItemDao(): VaultItemDao

    abstract fun ImageLabelDao(): ImageLabelDao

    abstract fun ImageTextDao(): ImageTextDao

    abstract fun ImageEmbeddingDao(): ImageEmbeddingDao

    abstract fun ImageHashDao(): ImageHashDao

    abstract fun AlbumDao(): AlbumDao

    companion object {
        private var db: GalleryDatabase? = null

        fun getInstance(context: Context): GalleryDatabase {
            if (db == null) {
                synchronized(GalleryDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, GalleryDatabase::class.java, "gallery.db")
                            .fallbackToDestructiveMigration()
                            .addMigrations(MIGRATION_4_5)
                            .addMigrations(MIGRATION_5_6)
                            .addMigrations(MIGRATION_6_7)
                            .addMigrations(MIGRATION_7_8)
                            .addMigrations(MIGRATION_8_9)
                            .addMigrations(MIGRATION_9_10)
                            .addMigrations(MIGRATION_10_11)
                            .addMigrations(MIGRATION_11_12)
                            .addMigrations(MIGRATION_12_13)
                            .addMigrations(MIGRATION_13_14)
                            .addMigrations(MIGRATION_14_15)
                            .addMigrations(MIGRATION_15_16)
                            .addMigrations(MIGRATION_16_17)
                            .addMigrations(MIGRATION_17_18)
                            .addMigrations(MIGRATION_18_19)
                            .build()
                    }
                }
            }
            return db!!
        }

        fun destroyInstance() {
            if (db?.isOpen == true) {
                db?.close()
            }
            db = null
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE media ADD COLUMN video_duration INTEGER default 0 NOT NULL")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `widgets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `widget_id` INTEGER NOT NULL, `folder_path` TEXT NOT NULL)")
                database.execSQL("CREATE UNIQUE INDEX `index_widgets_widget_id` ON `widgets` (`widget_id`)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `date_takens` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `full_path` TEXT NOT NULL, `filename` TEXT NOT NULL, `parent_path` TEXT NOT NULL, `date_taken` INTEGER NOT NULL, `last_fixed` INTEGER NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `favorites` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `full_path` TEXT NOT NULL, `filename` TEXT NOT NULL, `parent_path` TEXT NOT NULL)")

                database.execSQL("CREATE UNIQUE INDEX `index_date_takens_full_path` ON `date_takens` (`full_path`)")
                database.execSQL("CREATE UNIQUE INDEX `index_favorites_full_path` ON `favorites` (`full_path`)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE directories ADD COLUMN sort_value TEXT default '' NOT NULL")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE date_takens ADD COLUMN last_modified INTEGER default 0 NOT NULL")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE media ADD COLUMN media_store_id INTEGER default 0 NOT NULL")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `vault_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `encrypted_filename` TEXT NOT NULL, `original_filename` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `original_size_bytes` INTEGER NOT NULL, `date_added` INTEGER NOT NULL)")
                database.execSQL("CREATE UNIQUE INDEX `index_vault_items_encrypted_filename` ON `vault_items` (`encrypted_filename`)")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE vault_items ADD COLUMN thumbnail_filename TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE vault_items ADD COLUMN original_folder_path TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE vault_items ADD COLUMN date_taken INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE vault_items ADD COLUMN vault_album_name TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `image_labels` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "`media_path` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`confidence` REAL NOT NULL, " +
                        "`indexed_at` INTEGER NOT NULL)"
                )
                database.execSQL("CREATE INDEX `index_image_labels_media_path` ON `image_labels` (`media_path`)")
                database.execSQL("CREATE INDEX `index_image_labels_label` ON `image_labels` (`label`)")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `image_texts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "`media_path` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`indexed_at` INTEGER NOT NULL)"
                )
                database.execSQL("CREATE UNIQUE INDEX `index_image_texts_media_path` ON `image_texts` (`media_path`)")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `image_embeddings` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "`media_path` TEXT NOT NULL, " +
                        "`vec` BLOB NOT NULL, " +
                        "`indexed_at` INTEGER NOT NULL)"
                )
                database.execSQL("CREATE UNIQUE INDEX `index_image_embeddings_media_path` ON `image_embeddings` (`media_path`)")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `image_hashes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "`media_path` TEXT NOT NULL, " +
                        "`phash` INTEGER NOT NULL, " +
                        "`file_size` INTEGER NOT NULL, " +
                        "`indexed_at` INTEGER NOT NULL)"
                )
                database.execSQL("CREATE UNIQUE INDEX `index_image_hashes_media_path` ON `image_hashes` (`media_path`)")
                database.execSQL("CREATE INDEX `index_image_hashes_phash` ON `image_hashes` (`phash`)")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `albums` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "`name` TEXT NOT NULL, " +
                        "`cover_path` TEXT, " +
                        "`created_at` INTEGER NOT NULL)"
                )
                database.execSQL("CREATE UNIQUE INDEX `index_albums_name` ON `albums` (`name`)")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `album_items` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "`album_id` INTEGER NOT NULL, " +
                        "`media_path` TEXT NOT NULL, " +
                        "`added_at` INTEGER NOT NULL)"
                )
                database.execSQL("CREATE INDEX `index_album_items_album_id` ON `album_items` (`album_id`)")
                database.execSQL("CREATE INDEX `index_album_items_media_path` ON `album_items` (`media_path`)")
                database.execSQL("CREATE UNIQUE INDEX `index_album_items_album_id_media_path` ON `album_items` (`album_id`, `media_path`)")
            }
        }
    }
}
