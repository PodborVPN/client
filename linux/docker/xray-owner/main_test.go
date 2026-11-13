package main

import (
	"bytes"
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"testing"
	"time"
)

func TestHelperProcess(t *testing.T) {
	role := os.Getenv("OWNER_HELPER")
	if role == "" {
		return
	}
	switch role {
	case "supervisor":
		os.Setenv("OWNER_HELPER", "child")
		os.Exit(run(os.Args[len(os.Args)-3:]))
	case "owner":
		for {
			time.Sleep(time.Hour)
		}
	case "child":
		if data, err := io.ReadAll(os.Stdin); err != nil || len(data) != 0 {
			os.Exit(43)
		}
		if os.Getenv("CHILD_EXIT") != "" {
			os.Exit(42)
		}
		if os.Getenv("CHILD_IGNORE_TERM") != "" {
			signal.Ignore(syscall.SIGTERM)
		}
		if err := os.WriteFile(os.Getenv("CHILD_READY"), []byte(strconv.Itoa(os.Getpid())), 0600); err != nil {
			panic(err)
		}
		for {
			time.Sleep(time.Hour)
		}
	}
	os.Exit(0)
}

func helper(role string) *exec.Cmd {
	c := exec.Command(os.Args[0], "-test.run=TestHelperProcess")
	c.Env = append(os.Environ(), "OWNER_HELPER="+role)
	return c
}

func startSupervisor(t *testing.T, input *os.File, ignore bool, exit bool) (*exec.Cmd, string) {
	t.Helper()
	ready := filepath.Join(t.TempDir(), "ready")
	c := helper("supervisor")
	c.Args = append(c.Args, os.Args[0], "-test.run=TestHelperProcess", "child")
	c.Env = append(c.Env, "CHILD_READY="+ready)
	if ignore {
		c.Env = append(c.Env, "CHILD_IGNORE_TERM=1")
	}
	if exit {
		c.Env = append(c.Env, "CHILD_EXIT=1")
	}
	c.Stdin = input
	c.Stderr = os.Stderr
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Process.Kill(); _ = c.Wait() })
	return c, ready
}

func readyPID(t *testing.T, path string) int {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if b, err := os.ReadFile(path); err == nil {
			pid, err := strconv.Atoi(string(bytes.TrimSpace(b)))
			if err != nil {
				t.Fatal(err)
			}
			return pid
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("дочерний процесс не запустился")
	return 0
}

func waitStopped(t *testing.T, c *exec.Cmd, pid int) {
	t.Helper()
	done := make(chan error, 1)
	go func() { done <- c.Wait() }()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		if pid > 0 {
			_ = syscall.Kill(pid, syscall.SIGKILL)
		} else {
			_ = c.Process.Kill()
		}
		<-done
		t.Fatal("остановка превысила две секунды")
	}
	if _, err := os.Stat(fmt.Sprintf("/proc/%d", pid)); !os.IsNotExist(err) {
		t.Fatalf("дочерний процесс не собран: %d", pid)
	}
}

func TestOwnerDeathAndEOF(t *testing.T) {
	for _, death := range []bool{false, true} {
		for _, ignore := range []bool{false, true} {
			t.Run(fmt.Sprintf("kill=%v/ignore=%v", death, ignore), func(t *testing.T) {
				r, w, err := os.Pipe()
				if err != nil {
					t.Fatal(err)
				}
				defer r.Close()
				defer w.Close()
				var owner *exec.Cmd
				if death {
					owner = helper("owner")
					owner.ExtraFiles = []*os.File{w}
					if err = owner.Start(); err != nil {
						t.Fatal(err)
					}
					t.Cleanup(func() { _ = owner.Process.Kill(); _ = owner.Wait() })
					w.Close()
				}
				c, path := startSupervisor(t, r, ignore, false)
				pid := readyPID(t, path)
				if death {
					if err = owner.Process.Kill(); err != nil {
						t.Fatal(err)
					}
					_ = owner.Wait()
				} else {
					w.Close()
				}
				waitStopped(t, c, pid)
			})
		}
	}
}

func TestNaturalExitStatus(t *testing.T) {
	r, w, _ := os.Pipe()
	defer r.Close()
	defer w.Close()
	c, _ := startSupervisor(t, r, false, true)
	err := c.Wait()
	if err == nil || c.ProcessState.ExitCode() != 42 {
		t.Fatalf("статус дочернего процесса потерян: %v", err)
	}
}

func TestSignalStopsChild(t *testing.T) {
	r, w, _ := os.Pipe()
	defer r.Close()
	defer w.Close()
	c, path := startSupervisor(t, r, false, false)
	pid := readyPID(t, path)
	if err := c.Process.Signal(syscall.SIGTERM); err != nil {
		t.Fatal(err)
	}
	waitStopped(t, c, pid)
}

func TestOwnerClosedBeforeStart(t *testing.T) {
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	w.Close()
	c, path := startSupervisor(t, r, true, false)
	waitStopped(t, c, 0)
	if data, err := os.ReadFile(path); err == nil {
		pid, err := strconv.Atoi(string(data))
		if err != nil {
			t.Fatal(err)
		}
		if _, err := os.Stat(fmt.Sprintf("/proc/%d", pid)); !os.IsNotExist(err) {
			t.Fatalf("движок пережил закрытый канал: %d", pid)
		}
	}
}

func TestMissingBinary(t *testing.T) {
	c := helper("supervisor")
	c.Args = append(c.Args, filepath.Join(t.TempDir(), "missing"), "run", "unused")
	err := c.Run()
	if err == nil || c.ProcessState.ExitCode() != 1 {
		t.Fatalf("неверный результат старта: %v", err)
	}
}
