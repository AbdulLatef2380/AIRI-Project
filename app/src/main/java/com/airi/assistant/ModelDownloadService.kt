private fun startModelDownload() {

    val request = DownloadManager.Request(Uri.parse(modelUrl))
        .setTitle("AIRI Model")
        .setDescription("جاري تحميل ملف الذكاء الاصطناعي...")
        .setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        )
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
        .setDestinationInExternalFilesDir(
            this,
            null,
            "models/$modelName"
        )

    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val downloadId = dm.enqueue(request)

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {

            val id = intent?.getLongExtra(
                DownloadManager.EXTRA_DOWNLOAD_ID,
                -1
            )

            if (id == downloadId) {

                val query = DownloadManager.Query()
                    .setFilterById(downloadId)

                val cursor = dm.query(query)

                if (cursor.moveToFirst()) {

                    val statusIndex =
                        cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

                    val status = cursor.getInt(statusIndex)

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {

                        val modelFile = File(
                            getExternalFilesDir(null),
                            "models/$modelName"
                        )

                        if (modelFile.exists() &&
                            modelFile.length() > 100L * 1024 * 1024
                        ) {

                            Log.d("AIRI_DEBUG", "Model ready at: ${modelFile.absolutePath}")

                            Toast.makeText(
                                applicationContext,
                                "تم تحميل النموذج بنجاح!",
                                Toast.LENGTH_LONG
                            ).show()

                        } else {
                            Log.e("AIRI_DEBUG", "Model file corrupted or incomplete")
                        }

                    } else {
                        Log.e("AIRI_DEBUG", "Download failed")
                    }
                }

                cursor.close()
                unregisterReceiver(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED
        )
    } else {
        registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )
    }
}
