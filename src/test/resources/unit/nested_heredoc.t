use 5.38.0;
use strict;
use warnings;
use Test::More tests => 17;

# Test 1: Basic nested heredoc - empty heredoc content
my $v1 = "@{[ <<E1 ]}foo
E1
";
is($v1, "foo\n", 'Basic nested heredoc with text after');

# Test 2: Nested heredoc with content
my $v2 = "@{[ <<E2
content here
E2
]}result";
is($v2, "content here\nresult", 'Nested heredoc with content');

# Test 3: Multiple lines in heredoc
my $v3 = "@{[ <<E3
line1
line2
E3
]}";
is($v3, "line1\nline2\n", 'Nested heredoc with multiple lines');

# Test 4: Variable interpolation in heredoc
my $name = "World";
my $v4 = "@{[ <<E4
Hello $name
E4
]}!";
is($v4, "Hello World\n!", 'Nested heredoc with interpolation');

# Test 5: Empty heredoc
my $v5 = "@{[ <<E5
E5
]}text";
is($v5, "text", 'Empty nested heredoc');

# Test 6: Heredoc in concatenation
my $v6 = "start" . "@{[ <<E6
middle
E6
]}" . "end";
is($v6, "startmiddle\nend", 'Nested heredoc in concatenation');

# Test 7: Heredoc with special characters
my $v7 = "@{[ <<'E7'
Special chars: $@%
E7
]}";
is($v7, "Special chars: \$\@%\n", 'Nested heredoc with single quotes');

# Test 8: Indented heredoc
my $v8 = "@{[ <<~E8
    indented
    E8
]}";
is($v8, "indented\n", 'Nested indented heredoc');

# Test 9: Complex expression
my $x = 5;
my $v9 = "@{[ <<E9
Value: @{[ $x * 2 ]}
E9
]}";
is($v9, "Value: 10\n", 'Nested heredoc with embedded expression');

# Test 10: Heredoc in array
my @arr = ("@{[ <<E10
item
E10
]}");
is($arr[0], "item\n", 'Nested heredoc in array');

# Test 11: Heredoc in hash
my %hash = (key => "@{[ <<E11
value
E11
]}");
is($hash{key}, "value\n", 'Nested heredoc in hash');

# Test 12: Multiple heredocs in string
my $v12 = "@{[ <<E12 ]}first@{[ <<E13
E12
second
E13
]}";
is($v12, "firstsecond\n", 'Multiple nested heredocs');

# Test 13: Heredoc before string with nested heredoc
my $v13 = <<E13 . "@{[ <<E14 ]}text
outer heredoc line 1
outer heredoc line 2
E13
inner heredoc content
E14
after";
is($v13, "outer heredoc line 1\nouter heredoc line 2\ninner heredoc content\ntext\nafter",
   'Heredoc before string with nested heredoc');

# Test 14: Multiple heredocs before string with nested heredoc
my $v14 = <<E15 . <<E16 . "@{[ <<E17 ]}end
E15 content
E15
E16 content
E16
E17 content
E17
of string";
is($v14, "E15 content\nE16 content\nE17 content\nend\nof string",
   'Multiple heredocs before string with nested heredoc');

# Test 15: Complex case with heredoc containing the string declaration
my $v15 = <<E18 . "@{[ <<E19 ]}";
String with @{[ <<E20 ]}
E20 content
E20
 in heredoc E18
E18
E19 content
E19
# Note: There's an extra newline because the line with @{[ <<E20 ]} has its own newline
# plus the E20 heredoc adds "E20 content\n"
is($v15, "String with E20 content\n\n in heredoc E18\nE19 content\n",
   'Heredoc containing string with nested heredoc');

# Test 16: Error case - missing terminator for outer heredoc
eval q{
    my $bad = <<E21 . "@{[ <<E22 ]}
    E21 content
    # Missing E21 terminator
    E22 content
    E22
    ";
};
like($@, qr/Can't find string terminator/, 'Error for missing outer heredoc terminator');

# Test 17: Multiline single-quoted string with heredoc
my $v17 = <<E21 . 'single
E21 content
E21
quoted
string' . <<E22;
E22 content
E22

is($v17, "E21 content\nsingle\nquoted\nstringE22 content\n",
   'Multiline single-quoted string does not capture heredoc content');

done_testing();