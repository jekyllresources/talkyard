/**
 * Copyright (C) 2012-2013 Kaj Magnus Lindberg (born 1979)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package debiki

import com.debiki.core.Prelude._
import com.zaxxer.hikari.{HikariDataSource, HikariConfig}
import play.{api => p}
import play.api.Play
import play.api.Play.current


// COULD rename / move, to what, where?
object Debiki {


  def getPostgresHikariDataSource(): HikariDataSource = {

    def configStr(path: String) =
      Play.configuration.getString(path) getOrElse
        runErr("DwE93KI2", "Config value missing: "+ path)

    // I've hardcoded credentials to the test database here, so that it
    // cannot possibly happen, that you accidentally connect to the prod
    // database. (You'll never name the prod schema "debiki_test",
    // with "auto-deleted" as password?)
    def user =
      if (Play.isTest) "debiki_test"
      else configStr("debiki.postgresql.user")

    def password =
      if (Play.isTest) "auto-deleted"
      else sys.env.get("DEBIKI_POSTGRESQL_PASSWORD") getOrElse
        configStr("debiki.postgresql.password")

    def database =
      if (Play.isTest) "debiki_test"
      else configStr("debiki.postgresql.database")

    val server = configStr("debiki.postgresql.server")
    val port = configStr("debiki.postgresql.port").toInt

    play.Logger.info(s"""Connecting to database: $server:$port/$database as user $user""")

    // Weird now with Hikari I can no longer call setReadOnly or setTransactionIsolation. [5JKF2]
    val config = new HikariConfig()
    config.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource")
    config.setUsername(user)
    config.setPassword(password)
    config.addDataSourceProperty("serverName", server)
    config.addDataSourceProperty("portNumber", port)
    config.addDataSourceProperty("databaseName", database)

    // Feels safest. They write "rarely necessary ... only applies if autoCommit is disabled"
    // but autocommit *is* disabled.
    config.setIsolateInternalQueries(true)
    config.setAutoCommit(false)
    config.setConnectionTimeout(3*1000)
    config.setValidationTimeout(2*1000) // must be less than the connection timeout

    //config.setReadOnly(true) — then Flyway can no longer migrate.
    config.setTransactionIsolation("TRANSACTION_SERIALIZABLE")

    // Weird:
    // https://github.com/brettwooldridge/HikariCP#initialization:
    // "We strongly recommend setting this value" — ok, but to what?
    // "at least 30 seconds less than any database-level connection timeout" — don't they mean
    // "more" not "less"? setConnectionTimeout(2*1000) above is just 2 seconds.
    // config.setMaxLifetime(???)

    // Start even if the database is not accessible, and show a friendly please-start-the-database
    // error page. — Hmm, no, Flyway needs an okay connection.
    // config.setInitializationFailFast(false)

    // Caching prepared statements = anti pattern, see:
    // https://github.com/brettwooldridge/HikariCP#statement-cache — better let the drivers
    // + the database cache (instead of per connection in the pool) because then just 1 cache
    // for all connections. (If I understood correctly?)

    // Slow loggin: Configure in PostgreSQL instead.

    val dataSource = new HikariDataSource(config)

    // Currently I'm sometimes using > 1 connection per http request (will fix later),
    // so in order to avoid out-of-connection deadlocks, set a large pool size.
    // Better size: https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
    // connections = ((core_count * 2) + effective_spindle_count)
    // — let's assume 8 cores and a few disks --> say 20 connections.
    config.setMaximumPoolSize(100) // did I fix the bug everywhere? then change to 20

    dataSource
  }

}


// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqn list ft=scala

