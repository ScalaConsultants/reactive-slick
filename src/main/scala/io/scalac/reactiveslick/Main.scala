package io.scalac.reactiveslick

import akka.actor.ActorSystem
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.Source
import slick.backend.{StaticDatabaseConfig, DatabaseConfig}
import slick.driver.JdbcProfile
import slick.jdbc.meta.MTable
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Awaitable, Await, Future}

@StaticDatabaseConfig("file:src/main/resources/application.conf#postgres")
object Main extends App {
  val dbConfig = DatabaseConfig.forConfig[JdbcProfile]("postgres")
  val db = dbConfig.db // all database interactions are realised through this object
  import dbConfig.driver.api._ // imports all the DSL goodies for the configured database

  def await[T](a: Awaitable[T])(implicit ec: ExecutionContext) = Await.result(a, Duration.Inf)
  def awaitAndPrint[T](a: Awaitable[T])(implicit ec: ExecutionContext) = println(await(a))

  class Suppliers(tag: Tag) extends Table[(Int, String, BigDecimal)](tag, "supplier") {
    def id = column[Int]("supplier_id", O.PrimaryKey)
    def name = column[String]("name")
    def income = column[BigDecimal]("income", O.Default(0.0))

    def * = (id, name, income)
  }

  class Products(tag: Tag) extends Table[(Int, Int, String, BigDecimal, Int)](tag, "product") {
    def id = column[Int]("product_id", O.PrimaryKey, O.AutoInc)
    def supplierId = column[Int]("supplier_id")
    def name = column[String]("name")
    def price = column[BigDecimal]("price", O.Default(1.0))
    def unitsSold = column[Int]("units_sold", O.Default(0))

    def * = (id, supplierId, name, price, unitsSold)

    def supplier = foreignKey("supplier_fk", supplierId, TableQuery[Suppliers])(_.id)
  }

  val suppliers = TableQuery[Suppliers]
  val products = TableQuery[Products]


  val tablesExist: DBIO[Boolean] = MTable.getTables.map { tables =>
    val names = Vector(suppliers.baseTableRow.tableName, products.baseTableRow.tableName)
    names.intersect(tables.map(_.name.name)) == names
  }
  val create: DBIO[Unit] = (suppliers.schema ++ products.schema).create
  val createIfNotExist: DBIO[Unit] = tablesExist.flatMap(exist => if (!exist) create else DBIO.successful())
  val insertSuppliers: DBIO[Option[Int]] = suppliers.map(s => (s.id, s.name)) ++= Seq((1, "Fruits Co."), (2, "Birds R Us"))
  val insertProducts: DBIO[Option[Int]]  = products.map(p => (p.supplierId, p.name)) ++= Seq((1, "Banana"), (1, "Orange"), (2, "Norwegian Blue"))
  val reloadData: DBIO[Option[Int]]  = products.delete >> suppliers.delete >> insertSuppliers >> insertProducts
  val listSuppliers: DBIO[Seq[String]] = suppliers.map(_.name).result


  val suppliersNames: Future[Seq[String]] = db.run(createIfNotExist >> reloadData >> listSuppliers)

  awaitAndPrint(suppliersNames)

  object Streaming {
    implicit val system = ActorSystem("Sys")

    implicit val materializer = ActorFlowMaterializer()

    val action = suppliers.map(_.name).result
    val publisher = db.stream(action)
    val future = Source(publisher).map(_.toUpperCase).runForeach(println)
    future.onComplete(_ => system.shutdown())(system.dispatcher)

    await(future)
  }

  Streaming


  val action: DBIO[Seq[String]] = suppliers.map(_.name).result
  val future: Future[Seq[String]] = db.run(action)

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

  awaitAndPrint(db.run(sellProduct("Banana").transactionally))

  val suppliersNamesSQL: DBIO[Seq[String]] = tsql"""SELECT name FROM supplier"""
  awaitAndPrint(db.run(suppliersNamesSQL))

  db.close()
}
