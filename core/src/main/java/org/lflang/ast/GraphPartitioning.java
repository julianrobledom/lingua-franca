package org.lflang.ast;

import java.util.*;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
//import org.apache.commons.math3.linear.Array2DRowRealMatrix;

public class GraphPartitioning {

    public static List<List<List<Integer>>> getAllNodeCombination(List<Integer> ns, int m) {
        int n = ns.size();
        int[] a = new int[n + 1];
        for (int j = 1; j <= m; j++) {
            a[n - m + j] = j - 1;
        }
        return f(m, n, 0, n, a, ns, m);
    }

    private static List<List<Integer>> visit(int n, int[] a, List<Integer> ns, int m) {
        List<List<Integer>> ps = new ArrayList<List<Integer>>();
        for(int i = 0; i < m; i++) {
            ps.add(new ArrayList<>());
        }
        for (int j = 0; j < n; j++) {
            ps.get(a[j + 1]).add(ns.get(j));
        }
        return ps;
    }

    private static List<List<List<Integer>>> f(int mu, int nu, int sigma, int n, int[] a, List<Integer> ns, int m) {
        List<List<List<Integer>>> result = new ArrayList<>();
        if (mu == 2) {
            result.add(visit(n, a, ns, m));
        } else {
            result.addAll(f(mu - 1, nu - 1, (mu + sigma) % 2, n, a, ns, m));
        }
        if (nu == mu + 1) {
            a[mu] = mu - 1;
            result.add(visit(n, a, ns, m));
            while (a[nu] > 0) {
                a[nu] = a[nu] - 1;
                result.add(visit(n, a, ns, m));
            }
        } else if (nu > mu + 1) {
            if ((mu + sigma) % 2 == 1) {
                a[nu - 1] = mu - 1;
            } else {
                a[mu] = mu - 1;
            }
            if ((a[nu] + sigma) % 2 == 1) {
                result.addAll(b(mu, nu - 1, 0, n, a, ns, m));
            } else {
                result.addAll(f(mu, nu - 1, 0, n, a, ns, m));
            }
            while (a[nu] > 0) {
                a[nu] = a[nu] - 1;
                if ((a[nu] + sigma) % 2 == 1) {
                    result.addAll(b(mu, nu - 1, 0, n, a, ns, m));
                } else {
                    result.addAll(f(mu, nu - 1, 0, n, a, ns, m));
                }
            }
        }
        return result;
    }

    private static List<List<List<Integer>>> b(int mu, int nu, int sigma, int n, int[] a, List<Integer> ns, int m) {
        List<List<List<Integer>>> result = new ArrayList<>();
        if (nu == mu + 1) {
            while (a[nu] < mu - 1) {
                result.add(visit(n, a, ns, m));
                a[nu] = a[nu] + 1;
            }
            result.add(visit(n, a, ns, m));
            a[mu] = 0;
        } else if (nu > mu + 1) {
            if ((a[nu] + sigma) % 2 == 1) {
                result.addAll(f(mu, nu - 1, 0, n, a, ns, m));
            } else {
                result.addAll(b(mu, nu - 1, 0, n, a, ns, m));
            }
            while (a[nu] < mu - 1) {
                a[nu] = a[nu] + 1;
                if ((a[nu] + sigma) % 2 == 1) {
                    result.addAll(f(mu, nu - 1, 0, n, a, ns, m));
                } else {
                    result.addAll(b(mu, nu - 1, 0, n, a, ns, m));
                }
            }
            if ((mu + sigma) % 2 == 1) {
                a[nu - 1] = 0;
            } else {
                a[mu] = 0;
            }
        }
        if (mu == 2) {
            result.add(visit(n, a, ns, m));
        } else {
            result.addAll(b(mu - 1, nu - 1, (mu + sigma) % 2, n, a, ns, m));
        }
        return result;
    }

    public static List<List<List<Integer>>> findPartitionsOfSize(int[][] adjMat, int k) {
        int numNodes = adjMat.length;
        List<Integer> nodes = new ArrayList<>();
        for (int i = 0; i < numNodes; i++) {
            nodes.add(i);
        }
        List<List<List<Integer>>> a = getAllNodeCombination(nodes, k);

        // Filter Partitions: Each enclave must be a connected subgraph of the G
        List<List<List<Integer>>> myList = new ArrayList<>();
        for (List<List<Integer>> subset : a) {
            boolean valid = true;
            for (List<Integer> partition : subset) {
                if (partition.size() > 1) {

                    // Find adj matrix for subgraph
                    int[][] result = new int[partition.size()][partition.size()];
                    for (int i = 0; i < partition.size(); i++) {
                        for (int j = 0; j < partition.size(); j++) {
                            if(adjMat[partition.get(i)][partition.get(j)] == 1){
                                result[i][j] = adjMat[partition.get(i)][partition.get(j)];
                            }
                        }
                    }

                    // Lemma 2.3.1. Let 𝐺=(𝑉,𝐸) be a graph, and let 0=𝜆1≤𝜆2≤⋯≤𝜆𝑛 be the 
                    // eigenvalues of its Laplacian matrix. Then, 𝜆2>0 if and only if 𝐺 is connected.
                    double lapMat[][] = laplacian_matrix(result);
                    RealMatrix testMatrix = MatrixUtils.createRealMatrix(lapMat);
                    EigenDecomposition eigenDecomposition = new EigenDecomposition(testMatrix);
                    double[] eigenvalues = eigenDecomposition.getRealEigenvalues();
                    Arrays.sort(eigenvalues);

                    if (eigenvalues[1] <= 0) {
                        valid = false;
                        break;
                    }
                }
            }
            if (valid) {
                myList.add(subset);
            }
        }

        return myList;
    }


    public static double[][] laplacian_matrix(int[][] A){
        // Create matrix D
        double[][] D = new double[A.length][A.length];
        for (int i = 0; i < A.length; i++) {
            D[i][i] = Arrays.stream(A[i]).sum();
        }

        // Create matrix L
        double[][] L = new double[A.length][A.length];
        for (int i = 0; i < A.length; i++) {
            for (int j = 0; j < A.length; j++) {
                L[i][j] = D[i][j] - A[i][j];
            }
        }
        return L;
    }

    public static double[][] normalized_laplacian_matrix(int[][] A, double[][] D, double[][] L){
        // Create matrix D2
        double[][] D2 = new double[A.length][A.length];
        for (int i = 0; i < A.length; i++) {
            D2[i][i] = 1 / Math.sqrt(D[i][i]);
        }
        // Create matrix L2
        double[][] L2 = matrixMultiply(matrixMultiply(D2, L), D2);
        return L2;
    }

    // Helper method for matrix multiplication
    private static double[][] matrixMultiply(double[][] A, double[][] B) {
        int rowsA = A.length;
        int colsA = A[0].length;
        int colsB = B[0].length;
        double[][] result = new double[rowsA][colsB];

        for (int i = 0; i < rowsA; i++) {
            for (int j = 0; j < colsB; j++) {
                for (int k = 0; k < colsA; k++) {
                    result[i][j] += A[i][k] * B[k][j];
                }
            }
        }
        return result;
    }

    public static List<List<List<Integer>>> findPartitions(int[][] adjMatrix) {
        List<List<List<Integer>>> graphs = new ArrayList<List<List<Integer>>>();
        int numNodes = adjMatrix[0].length;
        for (int i = 2; i <= numNodes; i++) {
            graphs.addAll(findPartitionsOfSize(adjMatrix, i));
        }
        return graphs;
    }
}

