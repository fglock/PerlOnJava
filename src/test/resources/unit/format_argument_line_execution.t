use strict;
use warnings;
use Test::More;

our $format_argument_line_counter = 0;

{
    package FormatTieScalar;
    sub TIESCALAR { bless { value => '' }, shift }
    sub FETCH { $_[0]{value} }
    sub STORE { $_[0]{value} = $_[1] }
}

{
    package main;
    tie my $format_tied_value, 'FormatTieScalar';
    $format_tied_value = 'N' x 8;
    utf8::upgrade($format_tied_value);
    format FORMAT_TIED_UPGRADE =
^<<<<<<<<
$format_tied_value
.

    my $path = 'format_tied_upgrade.tmp';
    open my $fh, '>', $path or die "open $path: $!";
    select((select($fh), $~ = 'FORMAT_TIED_UPGRADE')[0]);
    write $fh;
    close $fh or die "close $path: $!";
    open my $read_fh, '<', $path or die "read $path: $!";
    my $rendered = do { local $/; <$read_fh> };
    close $read_fh or die "close $path after read: $!";
    unlink $path or die "unlink $path: $!";
    is($rendered, "NNNNNNNN\n", 'upgrading a tied format operand preserves tie magic');
}

format FORMAT_ARGUMENT_LINE_EXECUTION =
@###|@###
++ $format_argument_line_counter, 2 + 3
.

my $path = 'format_argument_line_execution.tmp';
open my $fh, '>', $path or die "open $path: $!";
select((select($fh), $~ = 'FORMAT_ARGUMENT_LINE_EXECUTION')[0]);
write $fh;
close $fh or die "close $path: $!";
open my $read_fh, '<', $path or die "read $path: $!";
my $rendered = do { local $/; <$read_fh> };
close $read_fh or die "close $path after read: $!";
unlink $path or die "unlink $path: $!";

is($format_argument_line_counter, 1,
    'write executes expressions in a format argument line');
is($rendered, "   1|   5\n",
    'write terminates the final picture line with a record separator');

format FORMAT_PAGINATION_TOP =
T
.

format FORMAT_PAGINATION_BODY =
L1
L2
L3
L4
.

{
    my $buffer = '';
    open my $pagination_fh, '>', \$buffer or die "open scalar handle: $!";
    my $old_fh = select $pagination_fh;
    local $^ = 'FORMAT_PAGINATION_TOP';
    local $~ = 'FORMAT_PAGINATION_BODY';
    local $= = 3;
    local $- = 0;
    write;
    select $old_fh;
    is($buffer, "T\nL1\nL2\n\fT\nL3\nL4\n",
        'write paginates a body format around the top format');
}

our $format_continuation_value = 'one two three';
format FORMAT_CONTINUATION_EXECUTION =
^<<<~~
$format_continuation_value
.

my $continuation_path = 'format_continuation_execution.tmp';
open my $continuation_fh, '>', $continuation_path or die "open $continuation_path: $!";
select((select($continuation_fh), $~ = 'FORMAT_CONTINUATION_EXECUTION')[0]);
write $continuation_fh;
close $continuation_fh or die "close $continuation_path: $!";
open my $continuation_read_fh, '<', $continuation_path or die "read $continuation_path: $!";
my $continuation_rendered = do { local $/; <$continuation_read_fh> };
close $continuation_read_fh or die "close $continuation_path after read: $!";
unlink $continuation_path or die "unlink $continuation_path: $!";

is($continuation_rendered, "one\ntwo\nthre\ne\n",
    'write repeats a continuation picture while its scalar operand has text');
is($format_continuation_value, '',
    'write consumes a continuation operand across repeated picture lines');

{
    my @format_rows = ([1, 'One'], [2, 'Two']);
    format FORMAT_LEXICAL_ARRAY_REPEAT =
@ @<<<~~
@{(shift @format_rows) || ["", ""]}
.

    my $rows_path = 'format_lexical_array_repeat.tmp';
    open my $rows_fh, '>', $rows_path or die "open $rows_path: $!";
    select((select($rows_fh), $~ = 'FORMAT_LEXICAL_ARRAY_REPEAT')[0]);
    write $rows_fh;
    close $rows_fh or die "close $rows_path: $!";
    open my $rows_read_fh, '<', $rows_path or die "read $rows_path: $!";
    my $rows_rendered = do { local $/; <$rows_read_fh> };
    close $rows_read_fh or die "close $rows_path after read: $!";
    unlink $rows_path or die "unlink $rows_path: $!";
    is($rows_rendered, "1 One\n2 Two\n",
        'a repeated format line consumes a captured lexical array');
}

format FORMAT_TRAILING_LITERAL_LINE =
@<<
'value'
}
.

my $trailing_literal_path = 'format_trailing_literal_line.tmp';
open my $trailing_literal_fh, '>', $trailing_literal_path
    or die "open $trailing_literal_path: $!";
select((select($trailing_literal_fh), $~ = 'FORMAT_TRAILING_LITERAL_LINE')[0]);
write $trailing_literal_fh;
close $trailing_literal_fh or die "close $trailing_literal_path: $!";
open my $trailing_literal_read_fh, '<', $trailing_literal_path
    or die "open $trailing_literal_path after write: $!";
my $trailing_literal_rendered = do { local $/; <$trailing_literal_read_fh> };
close $trailing_literal_read_fh or die "close $trailing_literal_path after read: $!";
unlink $trailing_literal_path or die "unlink $trailing_literal_path: $!";

is($trailing_literal_rendered, "val\n}\n",
    'write terminates a final literal format line with a record separator');

our $format_nul_value = 'gaga';
eval "format FORMAT_NUL_PICTURE = \n"
    . '@<<<' . "\0\n"
    . '$format_nul_value' . "\n"
    . '@<<<' . "\0\n"
    . '$format_nul_value' . "\n.\n";
die $@ if $@;

my $nul_picture_path = 'format_nul_picture.tmp';
open my $nul_picture_fh, '>', $nul_picture_path
    or die "open $nul_picture_path: $!";
select((select($nul_picture_fh), $~ = 'FORMAT_NUL_PICTURE')[0]);
write $nul_picture_fh;
close $nul_picture_fh or die "close $nul_picture_path: $!";
open my $nul_picture_read_fh, '<', $nul_picture_path
    or die "open $nul_picture_path after write: $!";
my $nul_picture_rendered = do { local $/; <$nul_picture_read_fh> };
close $nul_picture_read_fh or die "close $nul_picture_path after read: $!";
unlink $nul_picture_path or die "unlink $nul_picture_path: $!";

is($nul_picture_rendered, "gaga\0\ngaga\0\n",
    'an eval-defined format does not render its declaration newline');

{
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval q{
        format FORMAT_REDEFINITION_WARNING =
.
        format FORMAT_REDEFINITION_WARNING =
.
    };
    die $@ if $@;
    like(join('', @warnings), qr/^Format FORMAT_REDEFINITION_WARNING redefined at/,
        'a second format declaration emits a redefine warning');
}

{
    my $weekday = ${{qw[ Sun 0 Mon 1 Tue 2 Wed 3 Thu 4 Fri 5 Sat 6 ]}}{'Wed'};
    is($weekday, 3,
        'a direct braced dereference preserves a qword hash literal');
}

format FORMAT_BRACED_ARGUMENT_BLOCK =
@<<< @<<<
{foo=>"bar"} # this is a code block, not a hash reference
.

my $braced_argument_path = 'format_braced_argument_block.tmp';
open my $braced_argument_fh, '>', $braced_argument_path
    or die "open $braced_argument_path: $!";
select((select($braced_argument_fh), $~ = 'FORMAT_BRACED_ARGUMENT_BLOCK')[0]);
write $braced_argument_fh;
close $braced_argument_fh or die "close $braced_argument_path: $!";
open my $braced_argument_read_fh, '<', $braced_argument_path
    or die "open $braced_argument_path after write: $!";
my $braced_argument_rendered = do { local $/; <$braced_argument_read_fh> };
close $braced_argument_read_fh or die "close $braced_argument_path after read: $!";
unlink $braced_argument_path or die "unlink $braced_argument_path: $!";

is($braced_argument_rendered, "foo  bar\n",
    'a commented braced format argument executes as a code block');

format FORMAT_NESTED_BODY =
@<<<
{
    my $birds = 'birds';
    local *FORMAT_NESTED_BODY = *FORMAT_NESTED_INNER{FORMAT};
    write FORMAT_NESTED_BODY;
    format FORMAT_NESTED_INNER =
@<<<<<
$birds;
.
    'nest'
}
.

my $nested_format_path = 'format_nested_body.tmp';
open FORMAT_NESTED_BODY, '>', $nested_format_path
    or die "open $nested_format_path: $!";
write FORMAT_NESTED_BODY;
close FORMAT_NESTED_BODY or die "close $nested_format_path: $!";
open my $nested_format_read_fh, '<', $nested_format_path
    or die "open $nested_format_path after write: $!";
my $nested_format_rendered = do { local $/; <$nested_format_read_fh> };
close $nested_format_read_fh or die "close $nested_format_path after read: $!";
unlink $nested_format_path or die "unlink $nested_format_path: $!";

is($nested_format_rendered, "birds\nnest\n",
    'a nested format declaration executes inside its outer format argument');

eval q|
format FORMAT_INVALID_ARGUMENT =
@
@_ =~ s///
.
|;
eval { write FORMAT_INVALID_ARGUMENT };
like($@, qr/Undefined format/,
    'a format whose argument fails compilation is not registered');

{
    local $^A = '';
    formline '@... x', 'a';
    is($^A, "a    x", 'dot picture field is parsed and formatted');
}

{
no strict 'vars';
format FORMAT_LEXICAL_ARGUMENT_CONTEXT =
^*|^*
my $format_lexical_value = q/dd/, $format_lexical_value
.
}

my $lexical_argument_path = 'format_lexical_argument_context.tmp';
open FORMAT_LEXICAL_ARGUMENT_CONTEXT, '>', $lexical_argument_path
    or die "open $lexical_argument_path: $!";
write FORMAT_LEXICAL_ARGUMENT_CONTEXT;
close FORMAT_LEXICAL_ARGUMENT_CONTEXT or die "close $lexical_argument_path: $!";
open my $lexical_argument_read_fh, '<', $lexical_argument_path
    or die "open $lexical_argument_path after write: $!";
my $lexical_argument_rendered = do { local $/; <$lexical_argument_read_fh> };
close $lexical_argument_read_fh or die "close $lexical_argument_path after read: $!";
unlink $lexical_argument_path or die "unlink $lexical_argument_path: $!";

is($lexical_argument_rendered, "dd|\n",
    'a lexical format argument declaration yields one scalar field value');

{
    local $^A = '';
    formline '@<<', 'foxiness';
    is($^A, 'fox', 'picture width includes the leading field sigil');
}

{
    local $^A = '';
    formline '@0##', 1;
    is($^A, '0001', 'zero picture glyph pads numeric fields');
}

{
    local $^A = '';
    formline '@', 'a';
    is($^A, 'a', 'single at-sign picture is a one-character field');
}

{
    local $~ = '';
    eval { write }; ## no critic (ErrorHandling::RequireCheckingReturnValueOfEval)
    like($@, qr/Undefined format ""/, 'write preserves an empty format name in its diagnostic');
}

{
    local $~ = 'NOSUCHFORMAT';
    eval { write }; ## no critic (ErrorHandling::RequireCheckingReturnValueOfEval)
    like($@, qr/Undefined format "NOSUCHFORMAT"/,
        'write reports an unqualified missing format name');
}

{
    local $~ = "\0foo";
    eval { write }; ## no critic (ErrorHandling::RequireCheckingReturnValueOfEval)
    like($@, qr/Undefined format "\0foo"/,
        'write preserves a NUL-prefixed missing format name');
}

our $top_format_diagnostic_value = 'x';
format TOP_FORMAT_DIAGNOSTIC =
@<<
$top_format_diagnostic_value
.

{
    my $top_path = 'format_top_diagnostic.tmp';
    open my $top_fh, '>', $top_path or die "open $top_path: $!";
    my $previous_fh = select $top_fh;
    local $~ = 'TOP_FORMAT_DIAGNOSTIC';
    local $^ = '';
    eval { write $top_fh }; ## no critic (ErrorHandling::RequireCheckingReturnValueOfEval)
    select $previous_fh;
    close $top_fh or die "close $top_path: $!";
    unlink $top_path or die "unlink $top_path: $!";
    like($@, qr/Undefined top format ""/,
        'write reports an explicitly empty top-format name');
}

done_testing;
