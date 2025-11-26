package dev.dnbln;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static class A {
        public static class B {
            public static class C {
                public void foo() {
                    System.out.println("Hello, World!");
                }
            }
        }
    }

    public static void main(String[] args) {
        A.B.C c = new A.B.C();
        c.foo();
    }
}