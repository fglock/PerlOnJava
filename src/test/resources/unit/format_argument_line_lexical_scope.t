use warnings 'syntax';
use Test::More;

format FORMAT_ARGUMENT_LINE_LEXICAL_SCOPE =
^*|^*
my $format_local = q/dd/, $format_local
.

my $rendered = '';
open my $format_fh, '>', \$rendered
    or die "open scalar format handle: $!";
my $old_fh = select $format_fh;
local $~ = 'FORMAT_ARGUMENT_LINE_LEXICAL_SCOPE';
my @format_warnings;
local $SIG{__WARN__} = sub { push @format_warnings, @_ };
write;
select $old_fh;
close $format_fh or die "close scalar format handle: $!";

is($rendered, "dd|\n",
    'a lexical declared in a format argument line is visible to later expressions on that line');
is(scalar @format_warnings, 0,
    'a format-line lexical is not misreported as an unavailable captured lexical');

done_testing;
