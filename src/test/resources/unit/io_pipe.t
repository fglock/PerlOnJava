#!/usr/bin/env perl
use strict;
use warnings;
use utf8;

BEGIN {
    binmode STDERR, ':encoding(UTF-8)';
    binmode STDOUT, ':encoding(UTF-8)';
}

use Test::More;

my $test_counter = 0;

# Helper function to create unique test filenames
sub get_test_filename {
    return "test_io_pipe_" . (++$test_counter) . "_" . $$ . ".tmp";
}

# Helper function to cleanup test files
sub cleanup_file {
    my $filename = shift;
    unlink $filename if -e $filename;
}

# Cross-platform command detection
my $is_windows = ($^O eq 'MSWin32' || $^O eq 'cygwin');
my $shell_cmd = $is_windows ? 'cmd /c' : 'sh -c';
my $devnull = $is_windows ? 'NUL' : '/dev/null';

# Helper function to get cross-platform commands
sub get_cross_platform_commands {
    my %commands;

    if ($is_windows) {
        $commands{echo} = 'echo';
        $commands{cat} = 'type';  # Windows equivalent of cat
        $commands{sort} = 'sort';
        $commands{wc_lines} = 'find /c /v ""';  # Count lines on Windows
        $commands{grep} = 'findstr';
        $commands{sleep} = 'timeout /t';
    } else {
        $commands{echo} = 'echo';
        $commands{cat} = 'cat';
        $commands{sort} = 'sort';
        $commands{wc_lines} = 'wc -l';
        $commands{grep} = 'grep';
        $commands{sleep} = 'sleep';
    }

    return %commands;
}

# Helper to create test data
sub create_test_data {
    my $test_id = ++$test_counter;
    return (
        "Line 1 - Test $test_id\n",
        "Line 2 - Hello World\n",
        "Line 3 - UTF-8: café naïve résumé\n",
        "Line 4 - Numbers: 12345\n",
        "Line 5 - End of test\n"
    );
}

# Helper to normalize line endings for comparison
sub normalize_lines {
    my $text = shift;
    $text =~ s/\r\n/\n/g;  # Convert Windows CRLF to LF
    $text =~ s/\r/\n/g;    # Convert old Mac CR to LF
    return $text;
}

# Helper to trim whitespace (including Windows echo trailing spaces)
sub trim_whitespace {
    my $text = shift;
    return unless defined $text;
    $text =~ s/^\s+//;  # Remove leading whitespace
    $text =~ s/\s+$//;  # Remove trailing whitespace
    return $text;
}

subtest 'Output pipe tests (writing to external commands)' => sub {
    my %cmd = get_cross_platform_commands();

    subtest 'Basic output pipe with echo' => sub {
        my $test_data = "Hello from Perl pipe test";

        # Test: perl script | echo (though this is a bit unusual)
        # Better test: write to a command that processes input
        my $command = $is_windows ? 'findstr .*' : 'cat';

        open my $pipe, "| $command" or do {
            fail "Cannot open output pipe to '$command': $!";
            return;
        };

        print $pipe $test_data;
        my $close_result = close $pipe;
        my $exit_status = $? >> 8;

        ok($close_result, 'Output pipe closed successfully');
        is($exit_status, 0, 'Command exited with status 0');
    };

    subtest 'Output pipe with sort command' => sub {
        my @lines = (
            "zebra\n",
            "apple\n",
            "banana\n",
            "cherry\n"
        );

        # Create a temporary file to capture sorted output
        my $temp_file = get_test_filename();

        my $sort_cmd = "$cmd{sort} > $temp_file";

        open my $pipe, "| $sort_cmd" or do {
            fail "Cannot open output pipe to sort: $!";
            return;
        };

        print $pipe @lines;
        my $close_result = close $pipe;

        ok($close_result, 'Sort pipe closed successfully');

        # Read back the sorted result
        open my $result_fh, '<', $temp_file or do {
            fail "Cannot read temp file: $!";
            return;
        };
        my @sorted_lines = <$result_fh>;
        close $result_fh;

        # Normalize line endings
        @sorted_lines = map { normalize_lines($_) } @sorted_lines;

        my @expected = ("apple\n", "banana\n", "cherry\n", "zebra\n");
        is_deeply(\@sorted_lines, \@expected, 'Lines were sorted correctly');

        cleanup_file($temp_file);
    };
};

subtest 'Input pipe tests (reading from external commands)' => sub {
    my %cmd = get_cross_platform_commands();

    subtest 'Basic input pipe with echo' => sub {
        my $test_message = "Hello from command";
        my $echo_cmd = "$cmd{echo} $test_message";

        open my $pipe, "$echo_cmd |" or do {
            fail "Cannot open input pipe from echo: $!";
            return;
        };

        my $result = <$pipe>;
        my $close_result = close $pipe;
        my $exit_status = $? >> 8;

        ok($close_result, 'Input pipe closed successfully');
        is($exit_status, 0, 'Echo command exited with status 0');

        chomp $result if defined $result;
        # Trim whitespace to handle Windows echo trailing spaces
        $result = trim_whitespace($result);
        is($result, $test_message, 'Received expected message from echo');
    };

    subtest 'Multi-line input pipe' => sub {
        # Create a command that outputs multiple lines
        my $multi_echo_cmd;
        if ($is_windows) {
            $multi_echo_cmd = 'cmd /c "echo Line1& echo Line2& echo Line3"';
        } else {
            $multi_echo_cmd = 'printf "Line1\\nLine2\\nLine3\\n"';
        }

        open my $pipe, "$multi_echo_cmd |" or do {
            fail "Cannot open input pipe for multi-line test: $!";
            return;
        };

        my @lines = <$pipe>;
        my $close_result = close $pipe;

        ok($close_result, 'Multi-line input pipe closed successfully');

        # Normalize and check lines
        @lines = map { normalize_lines($_) } @lines;
        chomp @lines;
        # Trim whitespace to handle Windows echo trailing spaces
        @lines = map { trim_whitespace($_) } @lines;

        is(scalar(@lines), 3, 'Received 3 lines');
        is($lines[0], 'Line1', 'First line correct');
        is($lines[1], 'Line2', 'Second line correct');
        is($lines[2], 'Line3', 'Third line correct');
    };
};

subtest 'Bidirectional pipe tests' => sub {
    # Note: True bidirectional pipes are complex and platform-specific
    # We'll test the open modes that Perl supports

    subtest 'Test pipe open modes' => sub {
        my %cmd = get_cross_platform_commands();

        # Test different pipe syntaxes
        my @pipe_tests = (
            {
                name => 'Output pipe with leading pipe',
                mode => "| $cmd{cat}",
                type => 'output',
                data => "test output\n"
            },
            {
                name => 'Input pipe with trailing pipe',
                mode => "$cmd{echo} test_input |",  # Removed quotes for Windows
                type => 'input',
                expected => 'test_input'
            }
        );

        foreach my $test (@pipe_tests) {
            subtest $test->{name} => sub {
                my $pipe_success = open my $pipe, $test->{mode};

                if (!$pipe_success) {
                    fail "Cannot open pipe with mode '$test->{mode}': $!";
                    return;
                }

                ok($pipe_success, "Pipe opened successfully: $test->{name}");

                if ($test->{type} eq 'output') {
                    print $pipe $test->{data};
                } elsif ($test->{type} eq 'input') {
                    my $result = <$pipe>;
                    chomp $result if defined $result;
                    # Trim whitespace to handle Windows echo trailing spaces
                    $result = trim_whitespace($result);
                    is($result, $test->{expected}, "Input pipe returned expected data");
                }

                my $close_result = close $pipe;
                ok($close_result, "Pipe closed successfully: $test->{name}");
            };
        }
    };
};

subtest 'Error handling and edge cases' => sub {
    subtest 'Invalid command handling' => sub {
        my $invalid_cmd = "nonexistent_command_12345";

        # Test output pipe to invalid command
        my $output_pipe_opened = open my $out_pipe, "| $invalid_cmd 2>$devnull";

        if ($output_pipe_opened) {
            print $out_pipe "test\n";
            my $close_result = close $out_pipe;
            my $exit_status = $? >> 8;

            # The pipe might open successfully but fail on close
            ok(!$close_result || $exit_status != 0,
                'Invalid command in output pipe should fail');
        } else {
            ok(1, 'Output pipe to invalid command failed to open (expected)');
        }

        # Test input pipe from invalid command
        # On some systems, the shell might succeed even if the command fails
        my $input_pipe_opened = open my $in_pipe, "$invalid_cmd 2>$devnull |";

        if ($input_pipe_opened) {
            my $result = <$in_pipe>;
            my $close_result = close $in_pipe;
            my $exit_status = $? >> 8;
            my $child_signal = $? & 127;
            my $child_coredump = $? & 128;

            # Check various failure conditions
            my $command_failed = !$close_result || $exit_status != 0 ||
                !defined($result) || $child_signal != 0;

            ok($command_failed,
                'Invalid command in input pipe should fail or produce no output')
                or diag("close_result: $close_result, exit_status: $exit_status, " .
                "result: " . (defined($result) ? "'$result'" : "undef") .
                ", child_signal: $child_signal");
        } else {
            ok(1, 'Input pipe from invalid command failed to open (expected)');
        }
    };

    subtest 'Empty pipe handling' => sub {
        my %cmd = get_cross_platform_commands();

        # Test reading from a command that produces no output
        my $empty_cmd = $is_windows ? 'cmd /c "exit 0"' : 'true';

        open my $pipe, "$empty_cmd |" or do {
            fail "Cannot open pipe to empty command: $!";
            return;
        };

        my $result = <$pipe>;
        my $close_result = close $pipe;

        ok($close_result, 'Empty command pipe closed successfully');
        ok(!defined($result), 'No output from empty command');
    };
};

subtest 'UTF-8 handling through pipes' => sub {
    my %cmd = get_cross_platform_commands();

    subtest 'UTF-8 through output pipe' => sub {
        # Skip on Windows due to cmd.exe UTF-8 limitations
        if ($is_windows) {
            pass("Skipping UTF-8 output pipe test on Windows");
            return;
        }

        my $utf8_text = "UTF-8 test: café naïve résumé 世界";

        # Create a temporary file to capture output
        my $temp_file = get_test_filename();

        my $cat_cmd = "$cmd{cat} > $temp_file";

        open my $pipe, "| $cat_cmd" or do {
            fail "Cannot open UTF-8 output pipe: $!";
            return;
        };

        binmode $pipe, ':utf8';
        print $pipe $utf8_text;
        my $close_result = close $pipe;

        ok($close_result, 'UTF-8 output pipe closed successfully');

        # Read back the result
        open my $result_fh, '<:utf8', $temp_file or do {
            fail "Cannot read UTF-8 temp file: $!";
            return;
        };
        my $result = do { local $/; <$result_fh> };
        close $result_fh;

        is($result, $utf8_text, 'UTF-8 text preserved through output pipe');

        cleanup_file($temp_file);
    };

    subtest 'UTF-8 through input pipe' => sub {
        # Use simpler test for Windows
        my $utf8_text = $is_windows ? "UTF-8_test_cafe" : "UTF-8 input: café naïve résumé";

        # Create a command that echoes UTF-8 text
        my $echo_cmd;
        if ($is_windows) {
            # Windows cmd might have issues with UTF-8, so we'll use a simpler test
            $echo_cmd = "$cmd{echo} $utf8_text";
        } else {
            $echo_cmd = "printf '$utf8_text'";
        }

        open my $pipe, "$echo_cmd |" or do {
            fail "Cannot open UTF-8 input pipe: $!";
            return;
        };

        binmode $pipe, ':utf8';
        my $result = <$pipe>;
        my $close_result = close $pipe;

        ok($close_result, 'UTF-8 input pipe closed successfully');

        chomp $result if defined $result;
        # Trim whitespace to handle Windows echo trailing spaces
        $result = trim_whitespace($result);
        ok(defined($result) && length($result) > 0, 'Received UTF-8 data from input pipe');

        if ($is_windows) {
            is($result, $utf8_text, 'Received expected ASCII-safe UTF-8 data');
        }
    };
};

subtest 'Shell interpretation tests' => sub {
    subtest 'Shell metacharacters' => sub {
        my %cmd = get_cross_platform_commands();

        # Test that shell metacharacters work (command chaining)
        my $chained_cmd;
        if ($is_windows) {
            $chained_cmd = 'cmd /c "echo First& echo Second"';
        } else {
            $chained_cmd = 'echo First; echo Second';
        }

        open my $pipe, "$chained_cmd |" or do {
            fail "Cannot open pipe with shell metacharacters: $!";
            return;
        };

        my @lines = <$pipe>;
        my $close_result = close $pipe;

        ok($close_result, 'Shell metacharacter pipe closed successfully');

        @lines = map { normalize_lines($_) } @lines;
        chomp @lines;
        # Trim whitespace to handle Windows echo trailing spaces
        @lines = map { trim_whitespace($_) } @lines;

        is(scalar(@lines), 2, 'Received 2 lines from chained command');
        is($lines[0], 'First', 'First command output correct');
        is($lines[1], 'Second', 'Second command output correct');
    };
};

# Platform-specific information
diag("Running on: $^O");
diag("Is Windows: " . ($is_windows ? "Yes" : "No"));
diag("Perl version: $]");

done_testing();
