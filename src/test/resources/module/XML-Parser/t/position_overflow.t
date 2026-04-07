use Test::More tests => 9;
use XML::Parser;

# Test that current_byte, current_line, and current_column all return
# correct non-negative values and proper types (not truncated to int).
# See https://github.com/cpan-authors/XML-Parser/issues/36
# On 32-bit perls with XML_LARGE_SIZE expat, values could overflow.
# On 64-bit perls, line/column returned int (32-bit) despite XML_Size
# being unsigned long (64-bit), causing truncation for huge files.

my (@byte_pos, @line_pos, @col_pos);

my $parser = XML::Parser->new(
    Handlers => {
        Start => sub {
            my ($expat, $el) = @_;
            push @byte_pos, $expat->current_byte;
            push @line_pos, $expat->current_line;
            push @col_pos,  $expat->current_column;
        },
    },
);

# Multi-line XML with known positions
my $xml = "<root>\n  <child>text</child>\n  <child2/>\n</root>";
$parser->parse($xml);

# Byte positions
is($byte_pos[0], 0,  'current_byte for root is 0');
is($byte_pos[1], 9,  'current_byte for child is 9');
is($byte_pos[2], 31, 'current_byte for child2 is 31');

# Line numbers (1-based)
is($line_pos[0], 1, 'current_line for root is 1');
is($line_pos[1], 2, 'current_line for child is 2');
is($line_pos[2], 3, 'current_line for child2 is 3');

# Column numbers (0-based)
is($col_pos[0], 0, 'current_column for root is 0');
is($col_pos[1], 2, 'current_column for child is 2');
is($col_pos[2], 2, 'current_column for child2 is 2');
