package ch.epfl.tkvs.transactionmanager;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.junit.Test;

import ch.epfl.tkvs.transactionmanager.lockingunit.LockingUnit;


public class LockingUnitTest extends TestCase {

    private static boolean check = true;

    @Test
    public void testExclusiveLock() throws Exception {
        LockingUnit.instance.initOnlyExclusiveLock();
        check = true;

        Thread thread1 = new Thread(new Runnable() {

            @Override
            public void run() {
                LockingUnit.instance.lock("test", LockingUnit.ExclusiveLockType.LOCK);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    fail();
                }
                check = false;
                LockingUnit.instance.release("test", LockingUnit.ExclusiveLockType.LOCK);
            }
        });

        Thread thread2 = new Thread(new Runnable() {

            @Override
            public void run() {
                LockingUnit.instance.lock("test", LockingUnit.ExclusiveLockType.LOCK);
                assertEquals(check, false);
                LockingUnit.instance.release("test", LockingUnit.ExclusiveLockType.LOCK);
            }
        });

        thread1.run();
        Thread.sleep(2000);
        thread2.run();

        thread1.join();
        thread2.join();
        assertEquals(check, false);
    }

    @Test
    public void testDefaultLockRead() throws Exception {
        // Init with default parameters
        LockingUnit.instance.init();

        final Semaphore sem = new Semaphore(0);
        Thread thread1 = new Thread(new Runnable() {

            @Override
            public void run() {
                LockingUnit.instance.lock("test", LockingUnit.DefaultLockType.READ_LOCK);
                try {
                    if (!sem.tryAcquire(1, 5000, TimeUnit.MILLISECONDS)) {
                        fail("thread2 did not release the semaphore and was blocked on the read lock");
                    }
                } catch (InterruptedException e) {
                    fail();
                }
                LockingUnit.instance.release("test", LockingUnit.DefaultLockType.READ_LOCK);
            }
        });

        Thread thread2 = new Thread(new Runnable() {

            @Override
            public void run() {
                LockingUnit.instance.lock("test", LockingUnit.DefaultLockType.READ_LOCK);
                sem.release();
                LockingUnit.instance.release("test", LockingUnit.DefaultLockType.READ_LOCK);
            }
        });

        thread1.run();
        Thread.sleep(200);
        thread2.run();
    }

    @Test
    public void testDefaultLockWrite() throws Exception {
        // Init with default parameters
        LockingUnit.instance.init();

        final Semaphore sem = new Semaphore(0);
        Thread thread1 = new Thread(new Runnable() {

            @Override
            public void run() {
                LockingUnit.instance.lock("test", LockingUnit.DefaultLockType.WRITE_LOCK);
                try {
                    if (sem.tryAcquire(1, 5000, TimeUnit.MILLISECONDS)) {
                        fail("thread2 could release the semaphore and was not blocked on the write lock");
                    }
                } catch (InterruptedException e) {
                    fail();
                }
                LockingUnit.instance.release("test", LockingUnit.DefaultLockType.WRITE_LOCK);
            }
        });

        Thread thread2 = new Thread(new Runnable() {

            @Override
            public void run() {
                LockingUnit.instance.lock("test", LockingUnit.DefaultLockType.WRITE_LOCK);
                sem.release();
                LockingUnit.instance.release("test", LockingUnit.DefaultLockType.WRITE_LOCK);
            }
        });

        thread1.run();
        Thread.sleep(200);
        thread2.run();
    }

}