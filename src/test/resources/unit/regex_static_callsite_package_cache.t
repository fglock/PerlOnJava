use strict;
use warnings;
use Test::More tests => 6;

our @property_packages;

{
    package StaticCacheOwner;
    sub IsStaticCacheLetter {
        push @main::property_packages, __PACKAGE__;
        return "0041\n";
    }

    sub matches_owner_property {
        my ($value) = @_;
        return $value =~ /\p{IsStaticCacheLetter}/;
    }
}

{
    package StaticCacheCaller;
    sub IsStaticCacheLetter { return "0042\n"; }

    sub call_owner_twice {
        my $first = !!StaticCacheOwner::matches_owner_property('A');
        my $second = !!StaticCacheOwner::matches_owner_property('B');
        return ($first, $second, __PACKAGE__);
    }
}

my ($first, $second, $caller_package) = StaticCacheCaller::call_owner_twice();
ok($first, 'first static match resolves its lexical user property');
ok(!$second, 'cached static match retains the lexical property result');
is_deeply(\@property_packages, ['StaticCacheOwner'],
    'the lexical property definition is compiled once');
is($caller_package, 'StaticCacheCaller', 'caller package remains intact');

ok(StaticCacheOwner::matches_owner_property('A'),
    'later cache hit retains the lexical property');
is_deeply(\@property_packages, ['StaticCacheOwner'],
    'later cache hit does not recompile the property definition');
