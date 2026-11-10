// libxray-cli: конверт Invoke libXray из stdin, ответ в stdout.
package main

import (
	"fmt"
	"io"
	"os"

	libXray "github.com/xtls/libxray"
)

func main() {
	in, err := io.ReadAll(os.Stdin)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Println(libXray.Invoke(string(in)))
}
