/* Enable the legacy FTS3 tokenizer registration API for this connection.
 * The Java bridge registers its tokenizer module through SQLite's documented
 * fts3_tokenizer() SQL function after this extension is loaded.
 */
#include "sqlite3ext.h"

SQLITE_EXTENSION_INIT1

#if defined(_WIN32)
#define PERLONJAVA_SQLITE_EXPORT __declspec(dllexport)
#else
#define PERLONJAVA_SQLITE_EXPORT __attribute__((visibility("default")))
#endif

PERLONJAVA_SQLITE_EXPORT int sqlite3_perlonjava_tokenizer_enable_init(
    sqlite3 *db,
    char **error_message,
    const sqlite3_api_routines *api
) {
    int enabled = 0;
    int result;

    (void) error_message;
    SQLITE_EXTENSION_INIT2(api);
    result = sqlite3_db_config(
        db,
        SQLITE_DBCONFIG_ENABLE_FTS3_TOKENIZER,
        1,
        &enabled
    );
    if (result != SQLITE_OK) {
        return result;
    }
    return enabled ? SQLITE_OK : SQLITE_ERROR;
}
