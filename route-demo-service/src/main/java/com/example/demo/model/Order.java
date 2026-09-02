package com.example.demo.model;

public class Order {
    private Long id;
    private String product;
    private Double price;
    private String status;

    public Order() {}

    public Order(Long id, String product, Double price, String status) {
        this.id = id;
        this.product = product;
        this.price = price;
        this.status = status;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }

    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
