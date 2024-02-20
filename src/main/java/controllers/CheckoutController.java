package controllers;

import com.microsoft.sqlserver.jdbc.StringUtils;
import daos.OrderDao;
import daos.OrderItemDao;
import daos.UserDao;
import daos.VoucherDao;
import dbConnection.DbConnection;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import models.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class CheckoutController extends HttpServlet {
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    HttpSession session = request.getSession();
    User user = (User) session.getAttribute("user");
    if (user == null) {
      response.sendRedirect("/login");
      return;
    }

    if (request.getParameter("voucherCode") != null
        && !StringUtils.isEmpty(request.getParameter("voucherCode"))) {
      VoucherDao voucherDao = new VoucherDao();
      String voucherCode = request.getParameter("voucherCode");
      Voucher voucher = voucherDao.getByCode(voucherCode);
      session.setAttribute("voucher", voucher);
      response.sendRedirect("/checkout");
      return;
    }

    if (request.getParameter("removeVoucher") != null) {
      session.removeAttribute("voucher");
      response.sendRedirect("/checkout");
      return;
    }

    request.getRequestDispatcher("/checkout.jsp").forward(request, response);
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    HttpSession session = request.getSession();
    Cart cart = (Cart) session.getAttribute("cart");
    List<OrderItem> orderItems = null;
    if (cart == null) {
      session.setAttribute("message", "error-empty-cart");
      response.sendRedirect("/");
      return;
    } else {
      orderItems = cart.getOrderItems();
    }

    // An Order has the following attributes:
    // - userId (retrieved from session)
    // - orderId (auto-generated)
    // - orderTime (auto-generated)
    // - status ("pending" upon initial creation)
    // - paymentMethodId (default to 1 (cod))
    int userId = 0;
    if (session.getAttribute("user") != null) {
      User user = (User) session.getAttribute("user");
      userId = user.getUserId();
    } else {
      session.setAttribute("message", "error-empty-cart");
      response.sendRedirect("/");
      return;
    }

    byte paymentMethodId = Byte.parseByte(request.getParameter("paymentMethod"));
    // - firstName
    String firstName = request.getParameter("firstName");
    // - lastName
    String lastName = request.getParameter("lastName");
    // - phoneNumber
    String phoneNumber = request.getParameter("phone");
    // - address
    String address = request.getParameter("address");
    // - email
    String email = request.getParameter("email");
    // - total
    BigDecimal shipping = new BigDecimal(5);
    BigDecimal tax = cart.getTotal().divide(BigDecimal.valueOf(10), 2, RoundingMode.HALF_UP);
    BigDecimal total;
    if (session.getAttribute("voucher") != null) {
      Voucher voucher = (Voucher) session.getAttribute("voucher");
      // total = cart's total * (100 - voucher's percent) / 100 + shipping + tax
      total = cart.getTotal().multiply(BigDecimal.valueOf(100 - voucher.getVoucherPercent()))
          .divide(BigDecimal.valueOf(100))
          .add(shipping)
          .add(tax);
    } else {
      total = cart.getTotal().add(shipping).add(tax);
    }

    // - note (optional)
    String note = request.getParameter("note") != null
        ? request.getParameter("note").trim() : "";

    // Start checkout transaction
    try {
      DbConnection.begin(); // Set auto-commit to false

      // Whether the user wants to save their information to be used for future orders
      String isInfoSaved = request.getParameter("saveInfo") != null
          ? request.getParameter("saveInfo") : "false";
      if (isInfoSaved.equals("true")) {
        User user = (User) session.getAttribute("user");
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPhoneNumber(phoneNumber);
        user.setAddress(address);
        user.setRoleId((byte) 3); // Set role to "user"
        UserDao userDao = new UserDao();
        userDao.update(user);
      }

      // An Order needs to be created in order to fully create an OrderItem
      Order order = new Order(userId, paymentMethodId, firstName, lastName, address, phoneNumber, email, total, note);
      OrderDao orderDao = new OrderDao();
      orderDao.add(order);
      order = orderDao.getLatestOrder(); // Get the newly created order (with autogenerated id)

      // Then we finally add orderItems so the Order can return the order details
      OrderItemDao orderItemDao = new OrderItemDao();
      for (OrderItem OrderItem : orderItems) {
        OrderItem.setOrderId(order.getOrderId());
        orderItemDao.add(OrderItem);
      }

      DbConnection.commit();
    } catch (RuntimeException e) {
      DbConnection.rollback();
      session.setAttribute("message", "error-order");
      response.sendRedirect("/checkout");
      return;
    } finally {
      DbConnection.end(); // Set auto-commit to true
      DbConnection.close();
    }

    session.removeAttribute("cart");
    session.setAttribute("message", "success-order");
    response.sendRedirect("/");
  }
}
