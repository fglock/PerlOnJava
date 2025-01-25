use 5.38.0;
use strict;
use warnings;
use Test::More tests => 17;

# Test 1: Basic heredoc
my $basic_heredoc = <<'END';
This is a basic heredoc
END
is($basic_heredoc, "This is a basic heredoc\n", 'Basic heredoc');

# Test 2: Heredoc with indentation
my $indented_heredoc = <<'    END';
    This is an indented heredoc
    END
is($indented_heredoc, "    This is an indented heredoc\n", 'Indented heredoc');

# Test 3: Heredoc with trailing spaces
my $trailing_spaces_heredoc = <<'END';
This heredoc has trailing spaces    
END
is($trailing_spaces_heredoc, "This heredoc has trailing spaces    \n", 'Heredoc with trailing spaces');

# Test 4: Heredoc with special characters
my $special_chars_heredoc = <<'END';
This heredoc has special characters: !@#$%^&*()
END
is($special_chars_heredoc, "This heredoc has special characters: !@#\$%^&*()\n", 'Heredoc with special characters');

# Test 5: Heredoc with empty lines
my $empty_lines_heredoc = <<'END';
Line 1

Line 3
END
is($empty_lines_heredoc, "Line 1\n\nLine 3\n", 'Heredoc with empty lines');

# Test 6: Heredoc with multiple lines
my $multi_line_heredoc = <<'END';
Line 1
Line 2
Line 3
END
is($multi_line_heredoc, "Line 1\nLine 2\nLine 3\n", 'Multi-line heredoc');

# Test 7: Heredoc with Windows newlines (\r\n)
my $windows_newlines_heredoc = <<"END";
This heredoc uses Windows newlines\r\n
END
is($windows_newlines_heredoc, "This heredoc uses Windows newlines\r\n\n", 'Heredoc with Windows newlines');

# Test 8: Heredoc with Unix newlines (\n)
my $unix_newlines_heredoc = <<"END";
This heredoc uses Unix newlines\n
END
is($unix_newlines_heredoc, "This heredoc uses Unix newlines\n\n", 'Heredoc with Unix newlines');

# Test 9: Heredoc with mixed newlines (\r\n and \n)
my $mixed_newlines_heredoc = <<"END";
This heredoc has mixed newlines\r\n
And Unix newlines\n
END
is($mixed_newlines_heredoc, "This heredoc has mixed newlines\r\n\nAnd Unix newlines\n\n", 'Heredoc with mixed newlines');

# Test 11: Heredoc with quotes in the content
my $quotes_heredoc = <<'END';
This heredoc contains "quotes"
END
is($quotes_heredoc, "This heredoc contains \"quotes\"\n", 'Heredoc with quotes');

# Test 12: Heredoc with backslashes
my $backslashes_heredoc = <<'END';
This heredoc contains \backslashes\
END
is($backslashes_heredoc, "This heredoc contains \\backslashes\\\n", 'Heredoc with backslashes');

# Test 13: Multiple heredocs in a single statement
my $multiple_heredocs = <<'FIRST' . <<'SECOND';
First heredoc
FIRST
Second heredoc
SECOND
is($multiple_heredocs, "First heredoc\nSecond heredoc\n", 'Multiple heredocs in a single statement');

# Test 14: Heredoc with interpolation
my $interpolated_var = "interpolated";
my $interpolated_heredoc = <<"END";
This heredoc has an $interpolated_var variable
END
is($interpolated_heredoc, "This heredoc has an interpolated variable\n", 'Heredoc with interpolation');

# Test 15: Basic indented heredoc with <<~
my $basic_indented_heredoc = <<~'END';
    This is a basic indented heredoc
    END
is($basic_indented_heredoc, "This is a basic indented heredoc\n", 'Basic indented heredoc with <<~');

# Test 16: Indented heredoc with empty lines
my $indented_empty_lines_heredoc = <<~'END';
    Line 1

    Line 3
    END
is($indented_empty_lines_heredoc, "Line 1\n\nLine 3\n", 'Indented heredoc with empty lines');

# Test 17: Indented heredoc with mixed indentation
my $mixed_indentation_heredoc = <<~'END';
    Line 1
        Line 2
    Line 3
    END
is($mixed_indentation_heredoc, "Line 1\n    Line 2\nLine 3\n", 'Indented heredoc with mixed indentation');

# Test 19: Indented heredoc with interpolation
my $indented_interpolated_var = "interpolated";
my $indented_interpolated_heredoc = <<~"END";
    This heredoc has an $indented_interpolated_var variable
    END
is($indented_interpolated_heredoc, "This heredoc has an interpolated variable\n", 'Indented heredoc with interpolation');

done_testing();

