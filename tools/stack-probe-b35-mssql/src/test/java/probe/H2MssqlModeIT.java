package probe;

import org.springframework.boot.test.context.SpringBootTest;

/** Fast tier: the database contract on H2 in MSSQL mode (application.yml datasource). */
@SpringBootTest
class H2MssqlModeIT extends DatabaseContract {
}
