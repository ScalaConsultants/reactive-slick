# A Quick Overview of Slick 3.0

At ScalaC we've recently started adopting [Slick 3.0](http://slick.typesafe.com/news/2015/04/29/slick-3.0.0-released.html). If you haven't tried it yet, hopefully these notes will make the process go smoother.


## Configuration
Let's start with something simple. While not a revolutionary change, the streamlined approach to configuring the database connection shows effort put into making Slick more pleasant to work with. The configuration can be specified entirely using Typesafe Config. An example `application.conf` can look like this:

```
h2 {
  driver = "slick.driver.H2Driver$"
  db {
    url = "jdbc:h2:mem:slickdemo"
    driver = "org.h2.Driver"
    connectionPool = disabled
    keepAliveConnection = true
  }
}

postgres {
  driver = "slick.driver.PostgresDriver$"
  db {
    url = "jdbc:postgresql://127.0.0.1/slickdemo"
    driver = "org.postgresql.Driver"
    connectionPool = HikariCP
    user = slick
    password = ""
  }
}
```

The whole list of configuration options is available in the [API docs](http://slick.typesafe.com/doc/3.0.0/api/index.html#slick.jdbc.JdbcBackend$DatabaseFactoryDef@forConfig(String,Config,Driver\):Database).

Now all we need is to choose the appropriate config entry:

```
val dbConfig = DatabaseConfig.forConfig[JdbcProfile]("postgres")
val db = dbConfig.db // all database interactions are realised through this object
import dbConfig.driver.api._ // imports all the DSL goodies for the configured database

```

It could be even simpler if we decided a priori on what database engine to choose. Here we are making our application compatible with any database that matches the `JdbcProfile`. More elaborate multi-DB patterns can be found in this [Activator template](https://www.typesafe.com/activator/template/slick-multidb-reactive-platform-15v01)

Another feature to note here is that Slick now by default uses HikariCP for connection pooling (it still needs to provided as a build dependency). You can configure it to your needs, choose to disable it, or provide a third-party connection pool implementation, all via Typesafe Config.

## Actions
Slick 3.0 has been dubbed reactive and the main connsequence is that we would expect to see futures instead of plain result types when querying the database. However there's also an intermediate type `DBIOAction` which is a monad-like trait wrapping the result. In code they are usualy referred by the type alias `DBIO` and can be processed via the usual combinators like `map`, `flatMap`, `andThen`. Let's look at an example:

```
  val action: DBIO[Seq[String]] = suppliers.map(_.name).result
  val future: Future[Seq[String]] = db.run(action)
``` 

The query API remains mostly the same as in Slick 2.1 (there are some differences with regard to types and improved support for `Option` and outer joins, but in the usual way of working with Slick they might go unnoticed). When we're done composing the query we call the implicit `.result` on it which transforms it into a `DBIOAction`. The action represents the communication with the database, but that will not happen until the it is scheduled for execution with `db.run()` which will return a `Future` actual with the actual data (`db` is the object we created from configuration in the previous paragraph).

Queries that perform mutation (updates, inserts, deletes) are already actions - there's no need to transform them. Actions can be composed together to be run in a single session. The individual actions forming the composite will be executed sequentially, like in the example below:

```
  val suppliers = TableQuery[Suppliers]
  val products = TableQuery[Products]

  val tablesExist: DBIO[Boolean] = MTable.getTables.map { tables =>
    val names = Vector(suppliers.baseTableRow.tableName, products.baseTableRow.tableName)
    names.intersect(tables.map(_.name.name)) == names
  }
  val create: DBIO[Unit] = (suppliers.schema ++ products.schema).create
  val createIfNotExist: DBIO[Unit] = tablesExist.flatMap(exist => if (!exist) create else SuccessAction{})
  val insertSuppliers: DBIO[Option[Int]] = suppliers.map(s => (s.id, s.name)) ++= Seq((1, "Fruits Co."), (2, "Birds R Us"))
  val insertProducts: DBIO[Option[Int]]  = products.map(p => (p.supplierId, p.name)) ++= Seq((1, "Bananas"), (1, "Oranges"), (2, "Norwegian Blue"))
  val reloadData: DBIO[Option[Int]]  = products.delete >> suppliers.delete >> insertSuppliers >> insertProducts
  val listSuppliers: DBIO[Seq[String]] = suppliers.map(_.name).result

  val suppliersNames: Future[Seq[String]] = db.run(createIfNotExist >> reloadData >> listSuppliers)
```

As you can see several actions are executed in one go. `createIfNotExists` is componsed via `map` and `flatMap` transformations and the rest are linked together by `>>` (which is a shortcut for `DBIOAction#andThen`)

### Transacions
Having such actions chained together it should be easy to run them within a single DB transaction, and in fact it is. Consider this example:

```
  /**
   *  Update both the number of sold units for a product and total income for a supplier
   *  Not the best written function WRT missing records, but let's not complicate things ;)
   */
  def sellProduct(name: String): DBIO[BigDecimal] = {
    val productQuery = products.filter(_.name === name)
    for {
      (_, supplierId, _, price, unitsSold) <- productQuery.result.map(_.head)
      supplierIncomeQuery = suppliers.filter(_.id === supplierId).map(_.income)
      income <- supplierIncomeQuery.result.map(_.head)
      updatedIncome = income + price
      _ <- supplierIncomeQuery.update(updatedIncome)
      _ <- productQuery.map(_.unitsSold).update(unitsSold + 1)
    } yield updatedIncome
  }
```

All you need to do now is to run this action `.transactionally`:

```
db.run(sellProduct("Banana").transactionally)
```

### Plain SQL

One last thing to note about actions is that SQL interpolation using `sqlu"..."` and `sql"...".as[T]` also returns actions and running them is not different from other examples:

```
val suppliersNamesSQL = sql"""SELECT name FROM supplier""".as[String]
db.run(suppliersNamesSQL)
```

## Reactive Streams

Slick 3.0 also supports the Reactive Streams API. Any action that returns a collection can be converted to a DatabasePublisher (which implements `org.reactivestreams.Publisher`). Such Publisher can for example be used to construct a Flow with Akka Streams: 

```
val action = suppliers.map(_.name).result
val publisher = db.stream(action)
Source(publisher).map(_.toUpperCase).runForeach(println)
```

As I'm not going to cover [Reactive Streams](http://www.reactive-streams.org/) or [Akka Streams](http://www.typesafe.com/activator/template/akka-stream-scala) here, that's pretty much all there is to it :)

## Type-checked SQL

Another interesting feature introduced in Slick 3.0 is its ability to type check hand-written SQL statements. Here's how:

```
@StaticDatabaseConfig("file:src/main/resources/application.conf#postgres")
object Main extends App {
  val suppliersNamesSQL = tsql"""SELECT name FROM supplier"""
}
```

Things to note here are the use of `tsql` interpolator and `StaticDatabaseConfig` annotation which points the configuration file and the path in that configuration which defines the database used during compilation. Thanks to macro-magic Slick connect to this database to examine correctness of the queries with regard to syntax as well as types. Hence an additional bonus that we don't need to write `.as[String]` here (as with the regular `sql` interpolator), the proper types are inferred by Slick (your IDE might not be so kind).

Now, don't lose your head (as I nearly did) if doesn't work for you. There's either a bug or an omission in the documentation in that in order for the macro to work it is required to have an slf4j implementation provided as a dependency, and if using logback, it has to be defined in runtime scope (I learned this from this [blog post](http://underscore.io/blog/posts/2015/05/28/typechecking-sql.html) and there's also an [issue](https://github.com/slick/slick/issues/1174) reported):

```
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.1.2" % Runtime
```


So let's try to break some things to see how it performs:

Bad syntax:

```
val suppliersNamesSQL: DBIO[Seq[String]] = tsql"""SLECT name FROM supplier"""
```

```
exception during macro expansion: ERROR: syntax error at or near "SLECT"
[error]   Position: 1
[error]   val suppliersNamesSQL: DBIO[Seq[String]] = tsql"""SLECT name FROM supplier"""
```

Incorrect type:

```
val suppliersNamesSQL: DBIO[Seq[Int]] = tsql"""SELECT name FROM supplier"""
```
 
```
type mismatch;
[error]  found   : slick.profile.SqlStreamingAction[Vector[String],String,slick.dbio.Effect]
[error]  required: io.scalac.reactiveslick.Main.dbConfig.driver.api.DBIO[Seq[Int]]
[error]     (which expands to)  slick.dbio.DBIOAction[Seq[Int],slick.dbio.NoStream,slick.dbio.Effect.All]
[error]   val suppliersNamesSQL: DBIO[Seq[Int]] = tsql"""SELECT name FROM supplier"""
```

Quite neat. However, I don't see myself using this feature anytime soon. Making compilation dependent on a running DB instance and limited IDE support are blockers to me. Also it might hard to get this right if queries create or modify the schema. If you have a different opinion on this I'd very much welcome it in a comment.

## Conclusions 

That's all the major changes I wanted to present it this post. I think we can agree that Slick is heading the right direction, not only in following the reactive trend, but also in making the API more expresive and consistent that consequently makes our code look better (it even shows in the imports).

There are of course things to look forward to in subsequent releases. The DSL still has its limitations and generated SQL is far from perfect (see my [previous post](http://blog.scalac.io/2015/01/27/rough-experience-with-slick.html) which is still valid for the latest release). According to Stefan Zeiger query compilation will be improved in Slick 3.1 (see [here](http://www.infoq.com/news/2015/05/slick3))

Anyway, at ScalaC, we have already been using Slick 3.0 in some of our projects, and so far no major complaints. Since the query DSL hasn't changed noticeably the transition is rather easy (that doesn't necessarily mean migration) which is another reason I definitely recommend it for your next project.