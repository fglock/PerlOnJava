use strict;
use warnings;
use Test::More tests => 1;

{
    package Local::UndefStringification;
    use overload '""' => sub { undef }, fallback => 1;
}

my $subject = bless {}, 'Local::UndefStringification';
ok(!($subject =~ /Net::Server/), 'undef-like overloaded subject matches as the empty string');
