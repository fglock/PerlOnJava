use strict;
use warnings;
use Test::More;

sub say;

{
    package Local::Perl6Say;
    sub say {
        my $selected = select();
        my $handle = \*$selected;
        @_ = $_ unless @_;
        return print {$handle} @_, "\n";
    }
}

# Install the imported function at runtime, after the parser has seen the
# call. This models a compile-time import whose caller's source is parsed
# before the module's import code has populated the package slot.
{
    no warnings 'once';
    *main::say = \&Local::Perl6Say::say;
}

my $output = q{};
open my $fh, '>', \$output or die "open scalar handle: $!";
my $old = select $fh;
{
    local $\ = 'X';
    local $_ = 'value';
    say;
}
select $old;
close $fh or die "close scalar handle: $!";

is($output, "value\nX", 'imported zero-argument say honors localized output record separator');
done_testing;
