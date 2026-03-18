import java.util.*;

/**
 * MegaHeapTest.java
 *
 * 200+ проверок (на деле тысячи) для Heap.java.
 * Работает без JUnit: просто запускаешь main.
 */
public class MegaHeapTest {

    // ====== Настройки ======
    private static final long SEED = 123456789L;

    // Number of random scenarios per mode
    private static final int RANDOM_TRIALS_PER_MODE = 8;

    // Number of operations per random scenario
    private static final int OPS_PER_TRIAL = 350;

    // Насколько часто делать полную структурную валидацию (1 = после каждой операции)
    private static final int VALIDATE_EVERY = 1;

    // Operation probabilities in random tests
    private static final int P_INSERT = 35;
    private static final int P_DELETE_MIN = 15;
    private static final int P_DECREASE_KEY = 25;
    private static final int P_DELETE = 15;
    private static final int P_MELD = 10; // meld делаем реже

    // Диапазон ключей для insert (по заданию ключи > 0)
    private static final int KEY_MIN = 1;
    private static final int KEY_MAX = 1_000_000;

    // ====== Счётчик проверок ======
    private static long CHECKS = 0;

    private static void chk(boolean cond, String msg) {
        CHECKS++;
        if (!cond) {
            throw new RuntimeException("ПРОВЕРКА #" + CHECKS + " НЕ ПРОЙДЕНА: " + msg);
        }
    }

    private static void info(String s) {
        System.out.println(s);
    }

    // ====== Эталонная модель (multiset + хэндлы HeapItem) ======
    private static final class RefModel {
        // item -> key (текущее)
        private final IdentityHashMap<Heap.HeapItem, Integer> itemKey = new IdentityHashMap<>();
        // key -> count
        private final TreeMap<Integer, Integer> counts = new TreeMap<>();

        int size() {
            return itemKey.size();
        }

        boolean contains(Heap.HeapItem it) {
            return itemKey.containsKey(it);
        }

        int getKey(Heap.HeapItem it) {
            Integer v = itemKey.get(it);
            if (v == null) throw new IllegalStateException("RefModel: item не найден");
            return v;
        }

        Integer minKeyOrNull() {
            return counts.isEmpty() ? null : counts.firstKey();
        }

        void insert(Heap.HeapItem it, int key) {
            chk(!itemKey.containsKey(it), "RefModel: повторная вставка того же HeapItem");
            itemKey.put(it, key);
            counts.merge(key, 1, Integer::sum);
        }

        void decreaseKey(Heap.HeapItem it, int d) {
            chk(d > 0, "RefModel: decreaseKey с d<=0");
            Integer old = itemKey.get(it);
            chk(old != null, "RefModel: decreaseKey для уже удалённого item");
            int newKey = old - d;
            itemKey.put(it, newKey);

            // counts--
            decCount(old);
            // counts++
            counts.merge(newKey, 1, Integer::sum);
        }

        void delete(Heap.HeapItem it) {
            Integer old = itemKey.remove(it);
            chk(old != null, "RefModel: delete для уже удалённого item");
            decCount(old);
        }

        void deleteMin() {
            chk(!counts.isEmpty(), "RefModel: deleteMin на пустой модели");
            int mk = counts.firstKey();

            // Найти любой item с этим ключом
            Heap.HeapItem victim = null;
            for (Map.Entry<Heap.HeapItem, Integer> e : itemKey.entrySet()) {
                if (e.getValue() == mk) {
                    victim = e.getKey();
                    break;
                }
            }
            chk(victim != null, "RefModel: не найден item для minKey=" + mk);

            itemKey.remove(victim);
            decCount(mk);
        }

        private void decCount(int key) {
            Integer c = counts.get(key);
            chk(c != null && c > 0, "RefModel: decCount сломался для key=" + key);
            if (c == 1) counts.remove(key);
            else counts.put(key, c - 1);
        }

        // Для meld: слить model2 в this
        void absorb(RefModel other) {
            for (Map.Entry<Heap.HeapItem, Integer> e : other.itemKey.entrySet()) {
                Heap.HeapItem it = e.getKey();
                int k = e.getValue();
                chk(!this.itemKey.containsKey(it), "RefModel: meld конфликт item");
                this.itemKey.put(it, k);
                this.counts.merge(k, 1, Integer::sum);
            }
            other.itemKey.clear();
            other.counts.clear();
        }

        // List of all live items (for random operations)
        ArrayList<Heap.HeapItem> liveItems() {
            return new ArrayList<>(itemKey.keySet());
        }
    }

    // ====== Структурная проверка Heap (инварианты) ======
    private static void validateHeap(Heap h) {
        // 1) Базовые поля не отрицательные
        chk(h.size() >= 0, "size < 0");
        chk(h.numTrees() >= 0, "numTrees < 0");
        chk(h.numMarkedNodes() >= 0, "markedNodes < 0");
        chk(h.totalLinks() >= 0, "links < 0");
        chk(h.totalCuts() >= 0, "cuts < 0");
        chk(h.totalHeapifyCosts() >= 0, "heapifyCosts < 0");

        if (h.findMin() == null) {
            chk(h.size() == 0, "min==null, но size!=0");
            chk(h.numTrees() == 0, "min==null, но numTrees!=0");
            chk(h.numMarkedNodes() == 0, "min==null, но markedNodes!=0");
            return;
        }

        Heap.HeapItem minItem = h.findMin();
        chk(minItem.node != null, "minItem.node == null (для непустой кучи)");
        chk(minItem.node.item == minItem, "minItem.node.item != minItem");
        chk(minItem.node.item.node == minItem.node, "двусторонняя ссылка item.node не совпадает");

        // Обход всей структуры из root list
        IdentityHashMap<Heap.HeapNode, Boolean> seen = new IdentityHashMap<>();
        int rootsCount = 0;
        int markedCount = 0;
        int nodesCount = 0;

        int actualMin = Integer.MAX_VALUE;

        // root ring
        Heap.HeapNode start = minItem.node;
        Heap.HeapNode cur = start;

        // защитный лимит на случай сломанного next-цикла
        int safety = 0;

        do {
            chk(cur != null, "root cur == null");
            chk(cur.parent == null, "корень имеет parent != null");
            chk(cur.item != null, "узел без item");
            chk(cur.item.node == cur, "node.item.node != node (сломана связь)");
            chk(cur.prev != null && cur.next != null, "у корня prev/next == null");
            chk(cur.prev.next == cur, "root.prev.next != root");
            chk(cur.next.prev == cur, "root.next.prev != root");
            chk(!cur.mark, "корень помечен (mark=true), ожидается unmarked для корней");

            rootsCount++;

            // DFS стек для проверки поддеревьев
            ArrayDeque<Heap.HeapNode> st = new ArrayDeque<>();
            st.push(cur);

            while (!st.isEmpty()) {
                Heap.HeapNode x = st.pop();

                chk(x != null, "DFS: узел null");
                chk(x.item != null, "DFS: node.item == null");
                chk(x.item.node == x, "DFS: item.node не указывает на свой узел");

                // уникальность узла
                chk(!seen.containsKey(x), "Один и тот же HeapNode встречается дважды (цикл/дубликат)");
                seen.put(x, true);
                nodesCount++;

                int k = x.item.key;
                if (k < actualMin) actualMin = k;

                if (x.mark) markedCount++;
                if (x.parent == null) chk(!x.mark, "DFS: корень помечен");

                // children ring
                if (x.child == null) {
                    chk(x.rank == 0, "rank != 0 при child==null");
                } else {
                    chk(x.rank > 0, "rank <=0 при child!=null");
                    Heap.HeapNode cStart = x.child;
                    Heap.HeapNode c = cStart;
                    int childCnt = 0;

                    // safety для child ring
                    int childSafety = 0;

                    do {
                        chk(c != null, "child == null в child ring");
                        chk(c.parent == x, "у ребёнка неправильный parent");
                        chk(c.item != null, "у ребёнка item==null");
                        chk(c.item.node == c, "у ребёнка item.node != ребёнок");

                        chk(c.prev != null && c.next != null, "у ребёнка prev/next == null");
                        chk(c.prev.next == c, "child.prev.next != child");
                        chk(c.next.prev == c, "child.next.prev != child");

                        // heap-order: parent.key <= child.key
                        chk(x.item.key <= c.item.key, "нарушен heap-order: parent.key > child.key");

                        // ребёнка в стек
                        st.push(c);

                        childCnt++;
                        c = c.next;

                        childSafety++;
                        chk(childSafety < 2_000_000, "зацикливание в child ring (слишком длинно)");
                    } while (c != cStart);

                    chk(childCnt == x.rank, "rank не равен числу детей: rank=" + x.rank + ", children=" + childCnt);
                }
            }

            cur = cur.next;
            safety++;
            chk(safety < 2_000_000, "зацикливание в root ring (слишком длинно)");
        } while (cur != start);

        // Сверка агрегатов
        chk(nodesCount == h.size(), "узлов по обходу=" + nodesCount + " != size=" + h.size());
        chk(rootsCount == h.numTrees(), "корней по обходу=" + rootsCount + " != numTrees=" + h.numTrees());
        chk(markedCount == h.numMarkedNodes(), "marked по обходу=" + markedCount + " != markedNodes=" + h.numMarkedNodes());

        // min должен быть минимальным ключом
        chk(minItem.key == actualMin, "min.key=" + minItem.key + " != actualMin=" + actualMin);
    }

    // ====== Сверка Heap с RefModel (чёрный ящик + поля) ======
    private static void validateAgainstModel(Heap h, RefModel m) {
        chk(h.size() == m.size(), "size heap=" + h.size() + " != model=" + m.size());
        if (m.size() == 0) {
            chk(h.findMin() == null, "model пуст, но heap.findMin()!=null");
        } else {
            chk(h.findMin() != null, "model не пуст, но heap.findMin()==null");
            int hk = h.findMin().key;
            Integer mk = m.minKeyOrNull();
            chk(mk != null, "model.minKey null при size>0");
            chk(hk == mk, "min key heap=" + hk + " != model=" + mk);
        }
    }

    // ====== Deterministic Tests ======
    private static void deterministicTests(boolean lazyMelds, boolean lazyDecreaseKeys) {
        info("\n=== Deterministic Tests | lazyMelds=" + lazyMelds + ", lazyDecreaseKeys=" + lazyDecreaseKeys + " ===");
        Heap h = new Heap(lazyMelds, lazyDecreaseKeys);
        RefModel m = new RefModel();

        // Тест 1: пустая куча
        validateHeap(h);
        validateAgainstModel(h, m);

        // Тест 2: вставки и findMin
        Heap.HeapItem a = h.insert(10, "a"); m.insert(a, 10);
        Heap.HeapItem b = h.insert(3, "b");  m.insert(b, 3);
        Heap.HeapItem c = h.insert(7, "c");  m.insert(c, 7);
        validateHeap(h);
        validateAgainstModel(h, m);

        // Тест 3: deleteMin по одному
        h.deleteMin(); m.deleteMin();
        validateHeap(h);
        validateAgainstModel(h, m);

        // Тест 4: decreaseKey + проверка min
        // уменьшаем "a"(10) до 1 => d=9
        h.decreaseKey(a, 9); m.decreaseKey(a, 9);
        validateHeap(h);
        validateAgainstModel(h, m);

        // Тест 5: delete конкретного элемента
        h.delete(c); m.delete(c);
        validateHeap(h);
        validateAgainstModel(h, m);

        // Тест 6: добиваем всё deleteMin
        while (h.size() > 0) {
            h.deleteMin(); m.deleteMin();
            validateHeap(h);
            validateAgainstModel(h, m);
        }

        // Тест 7: meld (heap2 должен обнулиться)
        Heap h1 = new Heap(lazyMelds, lazyDecreaseKeys);
        Heap h2 = new Heap(lazyMelds, lazyDecreaseKeys);
        RefModel m1 = new RefModel();
        RefModel m2 = new RefModel();

        Heap.HeapItem x1 = h1.insert(5, "x1"); m1.insert(x1, 5);
        Heap.HeapItem x2 = h1.insert(50, "x2"); m1.insert(x2, 50);

        Heap.HeapItem y1 = h2.insert(4, "y1"); m2.insert(y1, 4);
        Heap.HeapItem y2 = h2.insert(6, "y2"); m2.insert(y2, 6);

        int linksBefore = h1.totalLinks() + h2.totalLinks();
        int cutsBefore  = h1.totalCuts()  + h2.totalCuts();
        int heapifyBefore = h1.totalHeapifyCosts() + h2.totalHeapifyCosts();

        h1.meld(h2);
        m1.absorb(m2);

        validateHeap(h1);
        validateAgainstModel(h1, m1);

        // heap2 должен стать пустым и обнулённым
        chk(h2.size() == 0, "после meld heap2.size != 0");
        chk(h2.findMin() == null, "после meld heap2.min != null");
        chk(h2.numTrees() == 0, "после meld heap2.numTrees != 0");
        chk(h2.numMarkedNodes() == 0, "после meld heap2.markedNodes != 0");
        validateHeap(h2);

        // счётчики должны не уменьшаться (строгих равенств не требуем, но сумма не должна упасть)
        chk(h1.totalLinks() >= linksBefore, "links после meld уменьшились");
        chk(h1.totalCuts() >= cutsBefore, "cuts после meld уменьшились");
        chk(h1.totalHeapifyCosts() >= heapifyBefore, "heapifyCosts после meld уменьшились");

        // Тест 8: delete(item) с переполнением (MAX_VALUE)
        Heap h3 = new Heap(lazyMelds, lazyDecreaseKeys);
        RefModel m3 = new RefModel();
        Heap.HeapItem big = h3.insert(Integer.MAX_VALUE, "big"); m3.insert(big, Integer.MAX_VALUE);
        Heap.HeapItem small = h3.insert(2, "small"); m3.insert(small, 2);
        validateHeap(h3);
        validateAgainstModel(h3, m3);

        // delete(big) должен удалить именно big (по модели станет min=2)
        h3.delete(big); m3.delete(big);
        validateHeap(h3);
        validateAgainstModel(h3, m3);

        // Тест 9: deleteMin не должен считаться как cut детей (проверка только для lazyDecreaseKeys=true обычно)
        // Создадим структуру: много вставок + deleteMin => консолидация => min имеет детей.
        if (lazyDecreaseKeys) {
            Heap h4 = new Heap(lazyMelds, true);
            RefModel m4 = new RefModel();
            ArrayList<Heap.HeapItem> its = new ArrayList<>();
            for (int i = 1; i <= 32; i++) {
                Heap.HeapItem it = h4.insert(i, "i" + i);
                its.add(it);
                m4.insert(it, i);
            }
            // сделаем deleteMin чтобы образовались деревья
            int cutsBeforeDelMin = h4.totalCuts();
            h4.deleteMin(); m4.deleteMin();

            // deleteMin не должен увеличивать cuts
            chk(h4.totalCuts() == cutsBeforeDelMin, "deleteMin увеличил cuts (не должно)");
            validateHeap(h4);
            validateAgainstModel(h4, m4);
        }
    }

    // ====== Random Tests ======
    private static void randomTests(boolean lazyMelds, boolean lazyDecreaseKeys, Random rnd) {
        info("\n=== Random Tests | lazyMelds=" + lazyMelds + ", lazyDecreaseKeys=" + lazyDecreaseKeys + " ===");

        for (int t = 1; t <= RANDOM_TRIALS_PER_MODE; t++) {
            Heap h = new Heap(lazyMelds, lazyDecreaseKeys);
            RefModel m = new RefModel();

            // Для meld мы будем иногда иметь вторую кучу
            Heap h2 = new Heap(lazyMelds, lazyDecreaseKeys);
            RefModel m2 = new RefModel();

            int lastLinks = 0, lastCuts = 0, lastHeapify = 0;

            for (int op = 1; op <= OPS_PER_TRIAL; op++) {
                int roll = rnd.nextInt(100);

                // Update the list of live items
                ArrayList<Heap.HeapItem> live = m.liveItems();
                ArrayList<Heap.HeapItem> live2 = m2.liveItems();

                if (roll < P_INSERT) {
                    // insert
                    int key = KEY_MIN + rnd.nextInt(KEY_MAX - KEY_MIN + 1);
                    Heap.HeapItem it = h.insert(key, "t" + t + "_op" + op);
                    m.insert(it, key);

                } else if (roll < P_INSERT + P_DELETE_MIN) {
                    // deleteMin
                    if (m.size() > 0) {
                        h.deleteMin();
                        m.deleteMin();
                    } else {
                        // пусто: просто проверяем, что deleteMin не падает
                        h.deleteMin();
                    }

                } else if (roll < P_INSERT + P_DELETE_MIN + P_DECREASE_KEY) {
                    // decreaseKey
                    if (m.size() > 0) {
                        Heap.HeapItem it = live.get(rnd.nextInt(live.size()));
                        int oldKey = m.getKey(it);

                        // делаем новое значение в диапазоне [0 .. oldKey-1] (d>0)
                        int newKey = (oldKey == 0) ? 0 : rnd.nextInt(oldKey);
                        int d = oldKey - newKey;
                        if (d == 0) d = 1; // гарантируем d>0
                        // если d>oldKey, newKey уйдет в отрицательное (в целом heap это выдержит, но по заданию лучше не надо)
                        if (d > oldKey) d = oldKey;

                        if (d > 0) {
                            h.decreaseKey(it, d);
                            m.decreaseKey(it, d);
                        }
                    }

                } else if (roll < P_INSERT + P_DELETE_MIN + P_DECREASE_KEY + P_DELETE) {
                    // delete(item)
                    if (m.size() > 0) {
                        Heap.HeapItem it = live.get(rnd.nextInt(live.size()));
                        h.delete(it);
                        m.delete(it);
                    }

                } else {
                    // meld
                    // наполняем h2 иногда
                    if (rnd.nextBoolean()) {
                        int key = KEY_MIN + rnd.nextInt(KEY_MAX - KEY_MIN + 1);
                        Heap.HeapItem it2 = h2.insert(key, "h2_" + t + "_op" + op);
                        m2.insert(it2, key);
                    }

                    // иногда сливаем h2 в h
                    if (m2.size() > 0 && rnd.nextInt(3) == 0) {
                        h.meld(h2);
                        m.absorb(m2);

                        // после meld h2 должен быть пуст
                        chk(h2.size() == 0, "random: после meld heap2.size != 0");
                        chk(h2.findMin() == null, "random: после meld heap2.min != null");
                        chk(h2.numTrees() == 0, "random: после meld heap2.numTrees != 0");
                        chk(h2.numMarkedNodes() == 0, "random: после meld heap2.markedNodes != 0");
                    }
                }

                // Проверка монотонности счётчиков
                chk(h.totalLinks() >= lastLinks, "totalLinks уменьшился");
                chk(h.totalCuts() >= lastCuts, "totalCuts уменьшился");
                chk(h.totalHeapifyCosts() >= lastHeapify, "totalHeapifyCosts уменьшился");
                lastLinks = h.totalLinks();
                lastCuts = h.totalCuts();
                lastHeapify = h.totalHeapifyCosts();

                // Полные проверки
                if (op % VALIDATE_EVERY == 0) {
                    validateAgainstModel(h, m);
                    validateHeap(h);
                }
            }

            info("  trial " + t + " OK (ops=" + OPS_PER_TRIAL + ")");
        }
    }

    // ====== Запуск всех режимов ======
    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        Random rnd = new Random(SEED);

        boolean[] bs = { false, true };

        for (boolean lazyMelds : bs) {
            for (boolean lazyDecreaseKeys : bs) {
                deterministicTests(lazyMelds, lazyDecreaseKeys);
                randomTests(lazyMelds, lazyDecreaseKeys, rnd);
            }
        }

        long ms = System.currentTimeMillis() - start;
        info("\nALL OK. Checks performed: " + CHECKS + " | time: " + ms + " ms");
        info("Seed=" + SEED + " (change to test different random scenarios)");
    }
}
