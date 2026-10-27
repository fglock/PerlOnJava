use strict;
use warnings;
use Test::More;

format FORMAT_LITERAL_ONLY_FIRST =
body
.

format FORMAT_LITERAL_ONLY_MULTIPLE =
first line
second line
.

sub render_format {
    my ($format_name) = @_;
    my $rendered = '';
    open my $fh, '>', \$rendered or die "open scalar handle: $!";
    my $previous = select $fh;
    local $~ = $format_name;
    write;
    select $previous;
    close $fh or die "close scalar handle: $!";
    return $rendered;
}

is(render_format('FORMAT_LITERAL_ONLY_FIRST'), "body\n",
    'a plain text first line is a literal picture');
is(render_format('FORMAT_LITERAL_ONLY_MULTIPLE'), "first line\nsecond line\n",
    'consecutive plain text lines remain literal pictures');

done_testing;
