use strict;
use warnings;
use Test::More;

# YAML::Any is part of PerlOnJava's bundled compatibility layer. On system
# Perl, this test is useful when the YAML::Any/YAML::XS prerequisites exist.
plan skip_all => 'YAML::Any is not installed' unless eval { require YAML::Any; 1 };
YAML::Any->import('Dump');

my $object = bless { This => 'this value', That => 'That Value' }, 'Foo';
my $yaml = Dump($object);

like($yaml, qr/perl\/hash:Foo/, 'YAML::Any preserves the blessed hash class tag');
like($yaml, qr/This:\s*this value/, 'YAML::Any serializes the blessed hash contents');
like($yaml, qr/That:\s*That Value/, 'YAML::Any serializes all blessed hash contents');

done_testing;
