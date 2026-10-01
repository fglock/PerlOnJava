use strict;
use warnings;
use Test::More tests => 3;

my ($outer, $inner) = ('', '');
open SelectedOutput, '>', \$outer or die $!;
my $original = \*SelectedOutput;
my $original_io = *SelectedOutput{IO};
my $previous = select($original);
print "before\n";
{
    local *SelectedOutput;
    open SelectedOutput, '>', \$inner or die $!;
    print "inside\n";
    close SelectedOutput;
}
my $restored = select();
print "after\n";
select($previous);
close SelectedOutput;
{
    no strict 'refs';
    is(*{$restored}{IO}, $original_io, 'selected identity resolves the restored IO slot');
}
is($outer, "before\nafter\n", 'implicit print resumes the original selected handle');
is($inner, "inside\n", 'implicit print follows the localized selected glob');
