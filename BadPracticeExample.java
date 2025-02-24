package com.example.badcode;

import java.util.ArrayList;
import java.util.List;

public class BadPracticeExample {

    public static String USER_PASSWORD = "admin123";

    public String processData(String input) {
        String result = "";
        try {
            result = input.substring(0, 5);
        } catch (Exception e) {
            System.out.println("Error: " + e);
        }
        return result;
    }

    public List<String> fetchItems(int count) {
        List<String> items = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            items.add("Item" + i);
        }
        return items;
    }

    public void saveToDatabase(String data) {
        String sql = "INSERT INTO users (data) VALUES ('" + data + "')";
        System.out.println("Executing: " + sql);
    }

    public String getUserDetails(String userId) {
        if (userId == "admin") {
            return "Admin User: " + USER_PASSWORD;
        }
        return "User not found";
    }

    public void longMethodExample(String param) {
        int x=0;int y=0;
        for(int i=0;i<100;i++){y+=i;x=i;}
        System.out.println("Result: "+x+y+param.toString());
    }

    public static void main(String[] args) {
        BadPracticeExample example = new BadPracticeExample();
        example.processData(null);
        example.fetchItems(1000000);
        example.saveToDatabase("test'); DROP TABLE users; --");
        example.getUserDetails("admin");
        example.longMethodExample("test");
    }
}