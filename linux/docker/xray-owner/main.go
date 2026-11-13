package main

import (
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"syscall"
	"time"
)

func main() { os.Exit(run(os.Args[1:])) }

func run(args []string) int {
	if len(args) == 0 {
		fmt.Fprintln(os.Stderr, "Не задан исполняемый файл движка")
		return 64
	}
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(stop)
	ownerClosed := make(chan struct{})
	go func() {
		_, _ = io.Copy(io.Discard, os.Stdin)
		close(ownerClosed)
	}()

	// Только владелец держит пишущий конец канала; движок stdin не наследует.
	c := exec.Command(args[0], args[1:]...)
	c.Stdout, c.Stderr = os.Stdout, os.Stderr
	if err := c.Start(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	finished := make(chan int, 1)
	go func() {
		_ = c.Wait()
		code := c.ProcessState.ExitCode()
		if code < 0 {
			code = 1
		}
		finished <- code
	}()
	select {
	case code := <-finished:
		return code
	case <-ownerClosed:
	case <-stop:
	}
	_ = c.Process.Signal(syscall.SIGTERM)
	timer := time.NewTimer(time.Second)
	defer timer.Stop()
	select {
	case <-finished:
	case <-timer.C:
		_ = c.Process.Kill()
		<-finished
	}
	return 0
}
