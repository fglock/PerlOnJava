use Test::More tests => 6;
use YAML ();
use B;

my $yaml = <<'EOM';
int: 23
float: 3.14
exp: 1e-5
EOM

my $data1 = do {
    local $YAML::Numify = 1;
    YAML::Load($yaml);
};
my $data2 = YAML::Load($yaml);

my $int1 = B::svref_2object(\$data1->{int})->FLAGS & (B::SVp_IOK | B::SVp_NOK);
my $int2 = B::svref_2object(\$data2->{int})->FLAGS & (B::SVp_IOK | B::SVp_NOK);
my $float1 = B::svref_2object(\$data1->{float})->FLAGS & (B::SVp_IOK | B::SVp_NOK);
my $float2 = B::svref_2object(\$data2->{float})->FLAGS & (B::SVp_IOK | B::SVp_NOK);
my $exp1 = B::svref_2object(\$data1->{exp})->FLAGS & (B::SVp_IOK | B::SVp_NOK);
my $exp2 = B::svref_2object(\$data2->{exp})->FLAGS & (B::SVp_IOK | B::SVp_NOK);

ok($int1, "int with \$YAML::Numify");
ok(! $int2, "int without \$YAML::Numify");
ok($float1, "float with \$YAML::Numify");
ok(! $float2, "float without \$YAML::Numify");
ok($exp1, "exp with \$YAML::Numify");
ok(! $exp2, "exp without \$YAML::Numify");
done_testing;
