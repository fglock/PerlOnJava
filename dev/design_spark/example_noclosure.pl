#!/usr/bin/env perl
use strict;
use warnings;
use SparkAdapter;

# Initialize Spark context
my $spark = SparkAdapter->new(
    app_name => "PerlSpark Analytics",
    master => "local[*]"
);

print "=== Processing Sales Data ===\n";

# Load data
my $sales_df = $spark->read_csv("sales_data.csv", {
    header => 1,
    infer_schema => 1
});

# Approach 1: Use SQL expressions instead of Perl closures
# This avoids serialization issues entirely
my $high_value_sales = $sales_df
    ->filter("amount > 1000")  # SQL expression string
    ->select(['customer_id', 'amount', 'product'])
    ->with_column('tax', "amount * 0.08");  # SQL expression

print "High value sales with tax:\n";
$high_value_sales->show(10);

# Approach 2: Pre-compiled Java functions for common operations
# The adapter would include a library of common transformations
my $customer_summary = $sales_df
    ->group_by('customer_id')
    ->agg({
        'amount' => ['sum', 'avg', 'count'],
        'product' => 'collect_list'
    })
    ->with_column('avg_rounded', $spark->functions->round_to_decimal(2, 'avg_amount'));

print "\n=== Customer Summary ===\n";
$customer_summary->show();

# Approach 3: Perl code generation - convert Perl to Java source
# The adapter compiles Perl logic to Java at runtime
my $regional_analysis = $sales_df
    ->join($spark->read_json("customers.json"), 'customer_id', 'left')
    ->perl_transform(q{
        # This Perl code gets compiled to Java
        sub process_row {
            my $row = shift;
            my $region_score = 0;
            $region_score = 100 if $row->{region} eq 'North';
            $region_score = 75  if $row->{region} eq 'South';
            $region_score = 50  if $row->{region} eq 'East';
            $region_score = 25  if $row->{region} eq 'West';
            
            return {
                %$row,
                region_score => $region_score,
                high_value => $row->{amount} > 1000 ? 'YES' : 'NO'
            };
        }
    });

print "\n=== Regional Analysis ===\n";
$regional_analysis->show();

# Approach 4: UDF (User Defined Function) registration
# Register Perl functions as named UDFs that can be serialized
$spark->register_perl_udf('classify_product', sub {
    my $product = shift;
    return "Electronics" if $product =~ /phone|laptop|tablet/i;
    return "Clothing" if $product =~ /shirt|pants|dress|shoes/i;
    return "Home" if $product =~ /furniture|appliance|decor/i;
    return "Other";
});

# The UDF gets compiled to a serializable Java function
my $categorized_sales = $sales_df
    ->with_column('category', "classify_product(product)")
    ->group_by(['category', 'region'])
    ->agg({'amount' => 'sum'});

print "\n=== Sales by Category and Region ===\n";
$categorized_sales->show();

# Approach 5: Collect-and-process pattern for complex Perl logic
# Bring data to driver node for Perl processing, then redistribute
print "\n=== Complex Perl Analysis ===\n";

# Collect small datasets to driver for Perl processing
my $regional_data = $sales_df
    ->group_by('region')
    ->agg({'amount' => ['sum', 'count', 'avg']})
    ->collect();  # Returns Perl array ref

# Process with full Perl capabilities on driver node
my @analysis_results;
for my $row (@$regional_data) {
    my $region = $row->{region};
    my $total = $row->{sum_amount};
    my $count = $row->{count_amount};
    my $avg = $row->{avg_amount};
    
    # Complex Perl logic that can't be easily serialized
    my $performance_tier;
    my $growth_potential;
    
    if ($total > 50000) {
        $performance_tier = "Excellent";
        $growth_potential = "Mature Market";
    } elsif ($total > 25000) {
        $performance_tier = "Good";
        $growth_potential = $count > 100 ? "Growing" : "Stable";
    } else {
        $performance_tier = "Needs Attention";
        $growth_potential = "High Potential";
    }
    
    push @analysis_results, {
        region => $region,
        total_sales => $total,
        transaction_count => $count,
        avg_transaction => $avg,
        performance_tier => $performance_tier,
        growth_potential => $growth_potential,
        efficiency_score => calculate_efficiency_score($total, $count, $avg)
    };
}

# Create new DataFrame from Perl-processed results
my $analysis_df = $spark->create_dataframe(\@analysis_results);
print "Regional performance analysis:\n";
$analysis_df->show();

# Approach 6: Pipeline pattern with intermediate serializable steps
print "\n=== Multi-stage Pipeline ===\n";

# Stage 1: Basic filtering and aggregation (SQL/Java)
my $stage1 = $sales_df
    ->filter("amount > 100")
    ->with_column('month', "month(sale_date)")
    ->group_by(['region', 'month'])
    ->agg({'amount' => ['sum', 'count']});

# Stage 2: Collect intermediate results for Perl processing
my $intermediate_data = $stage1->collect();

# Stage 3: Complex Perl analysis on collected data
my @trend_analysis;
my %region_trends;

for my $row (@$intermediate_data) {
    my $key = $row->{region};
    push @{$region_trends{$key}}, {
        month => $row->{month},
        total => $row->{sum_amount},
        count => $row->{count_amount}
    };
}

# Perl-based trend analysis
for my $region (keys %region_trends) {
    my $data = $region_trends{$region};
    my @sorted_data = sort { $a->{month} <=> $b->{month} } @$data;
    
    # Calculate trend using Perl's math capabilities
    my $trend = calculate_sales_trend(\@sorted_data);
    my $seasonality = detect_seasonality(\@sorted_data);
    
    push @trend_analysis, {
        region => $region,
        trend_direction => $trend->{direction},
        trend_strength => $trend->{strength},
        seasonal_pattern => $seasonality,
        peak_month => $trend->{peak_month}
    };
}

# Stage 4: Create final DataFrame with analysis results
my $trend_df = $spark->create_dataframe(\@trend_analysis);
print "Sales trend analysis:\n";
$trend_df->show();

# Save results using various formats
$analysis_df->write()
    ->mode("overwrite")
    ->option("header", "true")
    ->csv("output/regional_analysis");

$trend_df->write()
    ->mode("overwrite")
    ->json("output/trend_analysis");

# Helper functions that run on driver node
sub calculate_efficiency_score {
    my ($total, $count, $avg) = @_;
    # Complex calculation that would be hard to serialize
    return sprintf("%.2f", ($total / $count) * log($count) / 1000);
}

sub calculate_sales_trend {
    my $data = shift;
    # Implement linear regression or other trend analysis
    # This is complex Perl code that stays on the driver
    my $slope = 0;  # Simplified for example
    my $direction = $slope > 0 ? "Increasing" : "Decreasing";
    return {
        direction => $direction,
        strength => abs($slope),
        peak_month => $data->[-1]->{month}
    };
}

sub detect_seasonality {
    my $data = shift;
    # Complex seasonality detection logic
    return "Unknown";  # Simplified for example
}

print "\nProcessing complete!\n";
$spark->stop();

