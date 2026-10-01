use Config;
use Test::More tests => 6;
use constant constcow => *Config::{NAME};

my $globname = *Config::{NAME};
is($globname, 'Config::', 'stash typeglob NAME includes package terminator');

my $result = constcow =~ s/Config/David/r;
is($result, 'David::', 's///r accepts a COW constant');

$_ = '+,-';
tr/+\--/a\/c/;
is($_, 'a,/', 'escaped dash is literal in transliteration search list');

delete $::{does_not_exist};
eval { no warnings; $::{does_not_exist} =~ s/(?:)/*{'does_not_exist'}; 4/e };
like($@, qr/^Modification of a read-only value/,
    'substitution rejects a read-only vivified stash element');

eval { for (__PACKAGE__) { s/b/c/ } };
like($@, qr/^Modification of a read-only value/,
    'non-matching substitution rejects a read-only COW value');

my $nested = '';
{
    local *STDOUT;
    open STDOUT, '>', \$nested or die "redirect STDOUT: $!";
    local $SIG{__WARN__} = sub { };
    eval q{s//*_=0;s|0||;00.y0/e; print qq(ok\n)};
}
is($nested, "ok\n", 'nested substitutions with /e compile in a fresh eval');
